package com.lei.gateway.pool;

import io.netty.util.HashedWheelTimer;
import io.netty.util.Timeout;
import io.netty.util.Timer;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 全异步高性能并发资源池，参考 HikariCP ConcurrentBag 设计。
 *
 * <p>获取策略（全部基于 {@link CompletableFuture}，不阻塞调用线程）：
 * <ol>
 *   <li>ThreadLocal 快速路径 — 优先获取当前线程上次归还的条目</li>
 *   <li>共享列表 CAS 扫描 — 遍历 CopyOnWriteArrayList 通过 CAS 获取</li>
 *   <li>异步创建新条目 — 池未满时通过工厂异步创建</li>
 *   <li>PendingBorrow 等待队列 — 池满时注册 Future，归还时通过回调通知</li>
 * </ol>
 *
 * @param <T> 池化条目类型
 */
public class ConcurrentPool<T extends PoolEntry> implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ConcurrentPool.class);
    private static final Timer TIMEOUT_TIMER = new HashedWheelTimer(r -> {
        Thread t = new Thread(r, "pool-timeout-wheel");
        t.setDaemon(true);
        return t;
    }, 5, TimeUnit.MILLISECONDS, 512);
    private static final TimeoutScheduler DEFAULT_TIMEOUT_SCHEDULER =
            new NettyTimeoutScheduler(TIMEOUT_TIMER);

    private final PoolConfig config;
    private final PoolEntryFactory<T> factory;
    private final CopyOnWriteArrayList<T> sharedList;
    // 参考 HikariCP：ThreadLocal 存列表而非单个引用，requite 时加回列表，borrow 时从列表尾部取出并移除。
    // 用列表而非单个引用的性能原因：同一线程可能并发持有多个连接，单个引用每次 set 都覆盖，
    // 只缓存最后一个，其他归还的连接下次 borrow 时只能走第二级共享列表 CAS 扫描（有竞争开销）。
    // 列表可缓存该线程归还的所有连接，borrow 时直接从尾部取，完全无锁，快速路径命中率更高。
    // 使用 WeakReference 包装有两个原因：
    // 1. 防止跨线程 PoolEntry 泄露：remove(entry) 只能清理当前线程的列表，其他线程列表里可能还持有
    //    已 close 的 entry 的引用。WeakReference 确保 sharedList 移除后无强引用，GC 可直接回收，
    //    其他线程列表里的 WeakRef 自然变 null，borrow 时跳过即可。
    // 2. 防止自定义 ClassLoader 泄露：容器环境（Tomcat/OSGi）下，强引用会形成
    //    线程 → ThreadLocal → entry → ClassLoader 的引用链，导致 ClassLoader 无法卸载。
    private final ThreadLocal<List<WeakReference<T>>> threadLocalList;
    private final AtomicInteger totalEntries;
    private final ConcurrentLinkedQueue<PendingBorrow<T>> pendingBorrows;
    private final TimeoutScheduler timeoutScheduler;
    private volatile boolean closed;

    /**
     * 创建一个新的并发资源池。
     *
     * @param config 池配置
     * @param factory 条目创建工厂
     */
    public ConcurrentPool(PoolConfig config, PoolEntryFactory<T> factory) {
        this(config, factory, DEFAULT_TIMEOUT_SCHEDULER);
    }

    ConcurrentPool(PoolConfig config, PoolEntryFactory<T> factory,
            TimeoutScheduler timeoutScheduler) {
        this.config = new PoolConfig(config);
        this.factory = factory;
        this.sharedList = new CopyOnWriteArrayList<>();
        this.threadLocalList = ThreadLocal.withInitial(() -> new ArrayList<>(config.getThreadLocalCacheSize()));
        this.totalEntries = new AtomicInteger(0);
        this.pendingBorrows = new ConcurrentLinkedQueue<>();
        this.timeoutScheduler = Objects.requireNonNull(timeoutScheduler);
        this.closed = false;
    }

    /**
     * 异步从池中借用一个条目，不阻塞调用线程。
     *
     * <p>获取策略：ThreadLocal 快速路径 → 共享列表 CAS 扫描 → 异步创建新条目 → 等待队列。
     * ThreadLocal 快速路径适用于 Netty EventLoop 等固定线程模型（borrowAsync 与 requite 在同一线程）。
     *
     * @param timeout 最大等待时间
     * @param unit 时间单位
     * @return 包含池化条目的 CompletableFuture
     */
    public CompletableFuture<T> borrowAsync(long timeout, TimeUnit unit) {
        if (closed) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Pool is closed"));
        }

        // 第一级：ThreadLocal 快速路径（borrowAsync 由 EventLoop 线程调用，requite 也在同一线程，ThreadLocal 有效）
        List<WeakReference<T>> localList = threadLocalList.get();
        for (int i = localList.size() - 1; i >= 0; i--) {
            T entry = localList.remove(i).get();
            if (entry != null && entry.getState() == PoolEntry.STATE_NOT_IN_USE
                    && entry.compareAndSet(PoolEntry.STATE_NOT_IN_USE, PoolEntry.STATE_IN_USE)) {
                if (!entry.isAlive()) {
                    remove(entry);
                    continue;
                }
                entry.setLastAccessTime(System.nanoTime());
                return CompletableFuture.completedFuture(entry);
            }
        }

        // 第二级：共享列表 CAS 扫描
        for (T candidate : sharedList) {
            if (candidate.getState() == PoolEntry.STATE_NOT_IN_USE
                    && candidate.compareAndSet(PoolEntry.STATE_NOT_IN_USE, PoolEntry.STATE_IN_USE)) {
                if (!candidate.isAlive()) {
                    remove(candidate);
                    continue;
                }
                candidate.setLastAccessTime(System.nanoTime());
                return CompletableFuture.completedFuture(candidate);
            }
        }

        // 第二级：尝试异步创建新条目（池未满时）
        // CAS 冲突时做有限次重试，避免“一次失败就入等待队列”的假性池满。
        boolean reserved = false;
        for (int i = 0; i < 8; i++) {
            int current = totalEntries.get();
            if (current >= config.getMaxPoolSize()) {
                break;
            }
            if (totalEntries.compareAndSet(current, current + 1)) {
                reserved = true;
                break;
            }
        }
        if (reserved) {
            return factory.createAsync().thenApply(entry -> {
                if (closed) {
                    // close 与 createAsync 并发时，关闭后完成的条目必须直接销毁，避免泄漏。
                    totalEntries.decrementAndGet();
                    entry.close();
                    return null;
                }
                entry.compareAndSet(PoolEntry.STATE_NOT_IN_USE, PoolEntry.STATE_IN_USE);
                entry.setLastAccessTime(System.nanoTime());
                sharedList.add(entry);
                log.info("连接已创建(async) {} total={}", entry, totalEntries.get());
                return entry;
            }).exceptionally(e -> {
                totalEntries.decrementAndGet();
                log.error("异步创建池化条目失败", e);
                return null;
            }).thenCompose(entry -> {
                if (entry != null) {
                    return CompletableFuture.completedFuture(entry);
                }
                // 创建失败，进入等待队列
                if (closed) {
                    return CompletableFuture.failedFuture(
                            new IllegalStateException("Pool is closed"));
                }
                return enqueueWaiter(timeout, unit);
            });
        }

        // 第三级：池满，进入等待队列
        return enqueueWaiter(timeout, unit);
    }

    private CompletableFuture<T> enqueueWaiter(long timeout, TimeUnit unit) {
        CompletableFuture<T> future = new CompletableFuture<>();
        PendingBorrow<T> pending = new PendingBorrow<>(future);

        // 调用方主动 cancel 时标记状态，后续 requite 轮询可惰性清理。
        future.whenComplete((result, throwable) -> {
            if (future.isCancelled()) {
                pending.markCancelled();
            }
        });

        // 注册超时
        TimeoutHandle timeoutTask = timeoutScheduler.schedule(pending::timeout, timeout, unit);
        pending.setTimeoutTask(timeoutTask);

        pendingBorrows.offer(pending);

        if (closed) {
            pending.close();
            return future;
        }

        // 入队后再扫描一次，防止在入队前刚好有条目归还
        for (T candidate : sharedList) {
            if (candidate.getState() == PoolEntry.STATE_NOT_IN_USE
                    && candidate.compareAndSet(PoolEntry.STATE_NOT_IN_USE, PoolEntry.STATE_IN_USE)) {
                candidate.setLastAccessTime(System.nanoTime());
                if (pending.complete(candidate)) {
                    return future;
                } else {
                    // 等待者已超时/取消，归还条目。
                    candidate.compareAndSet(PoolEntry.STATE_IN_USE, PoolEntry.STATE_NOT_IN_USE);
                }
                break;
            }
        }

        return future;
    }

    /** 将条目归还到池中。 */
    public void requite(T entry) {
        if (entry.getState() != PoolEntry.STATE_IN_USE) {
            return;
        }
        entry.setLastAccessTime(System.nanoTime());

        // 优先尝试把条目直接交接给异步等待者，减少回池后被并发抢占的窗口。
        PendingBorrow<T> pending;
        while ((pending = pendingBorrows.poll()) != null) {
            if (pending.complete(entry)) {
                entry.setLastAccessTime(System.nanoTime());
                return;
            }
        }

        if (entry.compareAndSet(PoolEntry.STATE_IN_USE, PoolEntry.STATE_NOT_IN_USE)) {
            entry.setLastAccessTime(System.nanoTime());
            // 加回当前线程的 ThreadLocal 列表（上限由 PoolConfig.threadLocalCacheSize 控制）
            List<WeakReference<T>> localList = threadLocalList.get();
            if (localList.size() < config.getThreadLocalCacheSize()) {
                localList.add(new WeakReference<>(entry));
            }
        }
    }

    /**
     * 从池中移除条目（资源不可用时调用）。
     *
     * <p>先尝试 IN_USE→REMOVED：调用者自己持有的条目主动移除（如检测到连接断开）。
     * 再尝试 NOT_IN_USE→REMOVED：空闲清理器移除空闲条目。
     */
    public void remove(T entry) {
        if (!entry.compareAndSet(PoolEntry.STATE_IN_USE, PoolEntry.STATE_REMOVED)
                && !entry.compareAndSet(PoolEntry.STATE_NOT_IN_USE, PoolEntry.STATE_REMOVED)) {
            return;
        }
        sharedList.remove(entry);
        int remaining = totalEntries.decrementAndGet();
        threadLocalList.get().removeIf(ref -> ref.get() == entry);
        log.info("连接已移除 {} alive={} total={}", entry, entry.isAlive(), remaining);
        entry.close();
    }

    /**
     * 从池中退役条目但不关闭资源。
     *
     * <p>与 {@link #remove(Object)} 的唯一区别：不调用 {@code entry.close()}，
     * 由调用方决定关闭时机。适用于 GOAWAY、streamId 溢出等场景，
     * 连接上可能还有正在飞行的请求，不能立即关闭。
     */
    public void retire(T entry) {
        if (!entry.compareAndSet(PoolEntry.STATE_IN_USE, PoolEntry.STATE_REMOVED)
                && !entry.compareAndSet(PoolEntry.STATE_NOT_IN_USE, PoolEntry.STATE_REMOVED)) {
            return;
        }
        sharedList.remove(entry);
        int remaining = totalEntries.decrementAndGet();
        threadLocalList.get().removeIf(ref -> ref.get() == entry);
        log.info("连接已退役 {} alive={} total={}", entry, entry.isAlive(), remaining);
    }

    /** 返回当前正在使用的条目数。 */
    public int getActiveCount() {
        int count = 0;
        for (T entry : sharedList) {
            if (entry.getState() == PoolEntry.STATE_IN_USE) {
                count++;
            }
        }
        return count;
    }

    /** 返回当前空闲的条目数。 */
    public int getIdleCount() {
        int count = 0;
        for (T entry : sharedList) {
            if (entry.getState() == PoolEntry.STATE_NOT_IN_USE) {
                count++;
            }
        }
        return count;
    }

    /** 返回池中总条目数（活跃 + 空闲）。 */
    public int getTotalCount() {
        return sharedList.size();
    }

    /** 返回池配置的拷贝。 */
    public PoolConfig getConfig() {
        return new PoolConfig(config);
    }

    /**
     * 返回池中所有条目的不可变快照。
     *
     * @return 当前池中所有条目的列表副本
     */
    public List<T> getEntries() {
        return List.copyOf(sharedList);
    }

    /** 返回共享列表快照，供内部使用（如空闲清理）。 */
    CopyOnWriteArrayList<T> getSharedList() {
        return sharedList;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;

        // 取消所有异步等待者
        PendingBorrow<T> pending;
        while ((pending = pendingBorrows.poll()) != null) {
            pending.close();
        }

        // 清理当前线程的 ThreadLocal 列表
        threadLocalList.remove();

        for (T entry : sharedList) {
            entry.close();
        }
        sharedList.clear();
    }

    /**
     * 异步借用等待者，封装 CompletableFuture 和超时任务。
     */
    static final class PendingBorrow<T extends PoolEntry> {
        private static final int STATE_WAITING = 0;
        private static final int STATE_COMPLETED = 1;
        private static final int STATE_TIMED_OUT = 2;
        private static final int STATE_CANCELLED = 3;
        private static final int STATE_CLOSED = 4;

        private final CompletableFuture<T> future;
        private final AtomicInteger state = new AtomicInteger(STATE_WAITING);
        private volatile TimeoutHandle timeoutTask;

        PendingBorrow(CompletableFuture<T> future) {
            this.future = future;
        }

        void setTimeoutTask(TimeoutHandle timeoutTask) {
            this.timeoutTask = timeoutTask;
        }

        boolean complete(T entry) {
            // 先 CAS 抢占完成权，再尝试完成 future，避免“检查+更新”分离的竞态窗口。
            if (!state.compareAndSet(STATE_WAITING, STATE_COMPLETED)) {
                return false;
            }
            boolean completed = future.complete(entry);
            if (completed) {
                cancelTimeout();
                return true;
            }
            // 极端情况下（调用方手动完成/取消），纠正状态，交由调用方回收条目。
            if (future.isCancelled()) {
                state.set(STATE_CANCELLED);
            } else if (future.isCompletedExceptionally()) {
                state.set(STATE_CLOSED);
            }
            return completed;
        }

        void timeout() {
            if (!state.compareAndSet(STATE_WAITING, STATE_TIMED_OUT)) {
                return;
            }
            future.completeExceptionally(
                    new IllegalStateException("Timeout waiting for available pool entry"));
        }

        void close() {
            if (state.compareAndSet(STATE_WAITING, STATE_CLOSED)) {
                future.completeExceptionally(
                        new IllegalStateException("Pool is closed"));
                cancelTimeout();
            }
        }

        void markCancelled() {
            if (state.compareAndSet(STATE_WAITING, STATE_CANCELLED)) {
                cancelTimeout();
            }
        }

        private void cancelTimeout() {
            if (timeoutTask != null) {
                timeoutTask.cancel();
            }
        }
    }

    interface TimeoutScheduler {
        TimeoutHandle schedule(Runnable task, long timeout, TimeUnit unit);
    }

    interface TimeoutHandle {
        void cancel();
    }

    private static final class NettyTimeoutScheduler implements TimeoutScheduler {
        private final Timer timer;

        NettyTimeoutScheduler(Timer timer) {
            this.timer = timer;
        }

        @Override
        public TimeoutHandle schedule(Runnable task, long timeout, TimeUnit unit) {
            Timeout timeoutHandle = timer.newTimeout(ignored -> task.run(), timeout, unit);
            return timeoutHandle::cancel;
        }
    }
}
