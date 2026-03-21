package com.lei.gateway.pool;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 高性能并发资源池，参考 HikariCP ConcurrentBag 设计。
 *
 * <p>同步借用使用三级策略：
 * <ol>
 *   <li>ThreadLocal 快速路径 — 优先获取当前线程上次归还的条目</li>
 *   <li>共享列表 CAS 扫描 — 遍历 CopyOnWriteArrayList 通过 CAS 获取</li>
 *   <li>SynchronousQueue handoff — 等待其他线程归还或工厂创建新条目</li>
 * </ol>
 *
 * <p>异步借用 {@link #borrowAsync} 不阻塞调用线程，适用于 Netty EventLoop 等
 * 不可阻塞的线程模型。池满时将等待者放入队列，归还时通过回调通知。
 *
 * @param <T> 池化条目类型
 */
public class ConcurrentPool<T extends PoolEntry> implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ConcurrentPool.class);

    private final PoolConfig config;
    private final PoolEntryFactory<T> factory;
    private final CopyOnWriteArrayList<T> sharedList;
    private final SynchronousQueue<T> handoffQueue;
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
    private final LinkedBlockingDeque<PendingBorrow<T>> pendingBorrows;
    private final ScheduledExecutorService scheduler;
    private volatile boolean closed;

    /**
     * 创建一个新的并发资源池。
     *
     * @param config 池配置
     * @param factory 条目创建工厂
     */
    public ConcurrentPool(PoolConfig config, PoolEntryFactory<T> factory) {
        this.config = new PoolConfig(config);
        this.factory = factory;
        this.sharedList = new CopyOnWriteArrayList<>();
        this.handoffQueue = new SynchronousQueue<>(true);
        this.threadLocalList = ThreadLocal.withInitial(() -> new ArrayList<>(config.getThreadLocalCacheSize()));
        this.totalEntries = new AtomicInteger(0);
        this.pendingBorrows = new LinkedBlockingDeque<>();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "pool-timeout-scheduler");
            t.setDaemon(true);
            return t;
        });
        this.closed = false;
    }

    /**
     * 从池中借用一个条目，使用三级获取策略（阻塞版本）。
     *
     * <p>注意：不要在 Netty EventLoop 线程上调用此方法，会导致死锁。
     * EventLoop 场景请使用 {@link #borrowAsync}。
     *
     * @param timeout 最大等待时间
     * @param unit 时间单位
     * @return 状态为 {@link PoolEntry#STATE_IN_USE} 的池化条目
     * @throws InterruptedException 等待过程中被中断
     * @throws IllegalStateException 池已关闭或等待超时
     */
    public T borrow(long timeout, TimeUnit unit) throws InterruptedException {
        if (closed) {
            throw new IllegalStateException("Pool is closed");
        }

        // 第一级：ThreadLocal 快速路径（从列表尾部取，取出后从列表移除）
        List<WeakReference<T>> localList = threadLocalList.get();
        for (int i = localList.size() - 1; i >= 0; i--) {
            T entry = localList.remove(i).get();
            if (entry != null && entry.getState() == PoolEntry.STATE_NOT_IN_USE
                    && entry.compareAndSet(PoolEntry.STATE_NOT_IN_USE, PoolEntry.STATE_IN_USE)) {
                entry.setLastAccessTime(System.nanoTime());
                return entry;
            }
        }

        // 第二级：共享列表 CAS 扫描
        for (T candidate : sharedList) {
            if (candidate.getState() == PoolEntry.STATE_NOT_IN_USE
                    && candidate.compareAndSet(PoolEntry.STATE_NOT_IN_USE, PoolEntry.STATE_IN_USE)) {
                candidate.setLastAccessTime(System.nanoTime());
                return candidate;
            }
        }

        // 尝试创建新条目（池未满时）
        T newEntry = tryCreateEntry();
        if (newEntry != null) {
            return newEntry;
        }

        // 第三级：SynchronousQueue handoff — 等待归还
        long timeoutNanos = unit.toNanos(timeout);
        long deadline = System.nanoTime() + timeoutNanos;
        do {
            long waitNanos = deadline - System.nanoTime();
            if (waitNanos <= 0) {
                break;
            }
            T handed = handoffQueue.poll(waitNanos, TimeUnit.NANOSECONDS);
            if (handed != null
                    && handed.compareAndSet(PoolEntry.STATE_NOT_IN_USE, PoolEntry.STATE_IN_USE)) {
                handed.setLastAccessTime(System.nanoTime());
                return handed;
            }
        } while (!closed);

        throw new IllegalStateException("Timeout waiting for available pool entry");
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
                entry.setLastAccessTime(System.nanoTime());
                return CompletableFuture.completedFuture(entry);
            }
        }

        // 第二级：共享列表 CAS 扫描
        for (T candidate : sharedList) {
            if (candidate.getState() == PoolEntry.STATE_NOT_IN_USE
                    && candidate.compareAndSet(PoolEntry.STATE_NOT_IN_USE, PoolEntry.STATE_IN_USE)) {
                candidate.setLastAccessTime(System.nanoTime());
                return CompletableFuture.completedFuture(candidate);
            }
        }

        // 第二级：尝试异步创建新条目（池未满时）
        int current = totalEntries.get();
        if (current < config.getMaxPoolSize()
                && totalEntries.compareAndSet(current, current + 1)) {
            return factory.createAsync().thenApply(entry -> {
                entry.compareAndSet(PoolEntry.STATE_NOT_IN_USE, PoolEntry.STATE_IN_USE);
                entry.setLastAccessTime(System.nanoTime());
                sharedList.add(entry);
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
                return enqueueWaiter(timeout, unit);
            });
        }

        // 第三级：池满，进入等待队列
        return enqueueWaiter(timeout, unit);
    }

    private CompletableFuture<T> enqueueWaiter(long timeout, TimeUnit unit) {
        CompletableFuture<T> future = new CompletableFuture<>();
        PendingBorrow<T> pending = new PendingBorrow<>(future);

        // 注册超时
        ScheduledFuture<?> timeoutTask = scheduler.schedule(() -> {
            if (future.completeExceptionally(
                    new IllegalStateException("Timeout waiting for available pool entry"))) {
                pendingBorrows.remove(pending);
            }
        }, timeout, unit);
        pending.setTimeoutTask(timeoutTask);

        pendingBorrows.add(pending);

        // 入队后再扫描一次，防止在入队前刚好有条目归还
        for (T candidate : sharedList) {
            if (candidate.getState() == PoolEntry.STATE_NOT_IN_USE
                    && candidate.compareAndSet(PoolEntry.STATE_NOT_IN_USE, PoolEntry.STATE_IN_USE)) {
                candidate.setLastAccessTime(System.nanoTime());
                if (future.complete(candidate)) {
                    pendingBorrows.remove(pending);
                    timeoutTask.cancel(false);
                } else {
                    // future 已被超时完成，归还条目
                    candidate.compareAndSet(PoolEntry.STATE_IN_USE, PoolEntry.STATE_NOT_IN_USE);
                }
                break;
            }
        }

        return future;
    }

    private T tryCreateEntry() {
        int current = totalEntries.get();
        if (current >= config.getMaxPoolSize()) {
            return null;
        }
        if (!totalEntries.compareAndSet(current, current + 1)) {
            return null;
        }
        try {
            T entry = factory.create();
            entry.compareAndSet(PoolEntry.STATE_NOT_IN_USE, PoolEntry.STATE_IN_USE);
            entry.setLastAccessTime(System.nanoTime());
            sharedList.add(entry);
            return entry;
        } catch (Exception e) {
            totalEntries.decrementAndGet();
            log.error("创建池化条目失败", e);
            return null;
        }
    }

    /** 将条目归还到池中。 */
    public void requite(T entry) {
        if (entry.compareAndSet(PoolEntry.STATE_IN_USE, PoolEntry.STATE_NOT_IN_USE)) {
            entry.setLastAccessTime(System.nanoTime());

            // 优先尝试通知异步等待者
            PendingBorrow<T> pending;
            while ((pending = pendingBorrows.poll()) != null) {
                if (entry.compareAndSet(PoolEntry.STATE_NOT_IN_USE, PoolEntry.STATE_IN_USE)) {
                    entry.setLastAccessTime(System.nanoTime());
                    if (pending.complete(entry)) {
                        return;
                    }
                    // 该等待者已超时，归还条目继续尝试下一个
                    entry.compareAndSet(PoolEntry.STATE_IN_USE, PoolEntry.STATE_NOT_IN_USE);
                } else {
                    // 条目被其他线程抢走，插回队头保证 FIFO
                    pendingBorrows.offerFirst(pending);
                    return;
                }
            }

            // 无异步等待者，尝试 handoff 给同步等待者
            if (!handoffQueue.offer(entry)) {
                // 无同步等待者，加回当前线程的 ThreadLocal 列表（上限由 PoolConfig.threadLocalCacheSize 控制）
                List<WeakReference<T>> localList = threadLocalList.get();
                if (localList.size() < config.getThreadLocalCacheSize()) {
                    localList.add(new WeakReference<>(entry));
                }
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
        totalEntries.decrementAndGet();
        // 清理当前线程 ThreadLocal 列表里对该条目的引用（参考 HikariCP remove 实现）
        threadLocalList.get().removeIf(ref -> ref.get() == entry);
        entry.close();
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
            pending.cancel();
        }

        // 关闭超时调度器
        scheduler.shutdownNow();

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
        private final CompletableFuture<T> future;
        private volatile ScheduledFuture<?> timeoutTask;

        PendingBorrow(CompletableFuture<T> future) {
            this.future = future;
        }

        void setTimeoutTask(ScheduledFuture<?> timeoutTask) {
            this.timeoutTask = timeoutTask;
        }

        boolean complete(T entry) {
            boolean completed = future.complete(entry);
            if (completed && timeoutTask != null) {
                timeoutTask.cancel(false);
            }
            return completed;
        }

        void cancel() {
            future.completeExceptionally(
                    new IllegalStateException("Pool is closed"));
            if (timeoutTask != null) {
                timeoutTask.cancel(false);
            }
        }
    }
}
