package com.lei.gateway.pool;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ConcurrentPoolTest {

    private PoolConfig config;
    private PoolEntryFactory<TestPoolEntry> factory;

    @BeforeEach
    void setUp() {
        config = new PoolConfig();
        config.setMaxPoolSize(3);
        config.setMaxIdleTimeSeconds(5);
        config.setConnectionTimeoutMillis(200);
        factory = () -> CompletableFuture.completedFuture(new TestPoolEntry());
    }

    /** 辅助方法：异步借用并同步等待结果。 */
    private static TestPoolEntry borrowSync(ConcurrentPool<TestPoolEntry> pool,
            long timeout, TimeUnit unit) throws Exception {
        return pool.borrowAsync(timeout, unit).get(timeout + 200, TimeUnit.MILLISECONDS);
    }

    @Test
    void borrowCreatesNewEntryWhenPoolIsEmpty() throws Exception {
        try (ConcurrentPool<TestPoolEntry> pool = new ConcurrentPool<>(config, factory)) {
            TestPoolEntry entry = borrowSync(pool, 100, TimeUnit.MILLISECONDS);

            assertThat(entry).isNotNull();
            assertThat(entry.getState()).isEqualTo(PoolEntry.STATE_IN_USE);
            assertThat(pool.getTotalCount()).isEqualTo(1);
            assertThat(pool.getActiveCount()).isEqualTo(1);
            assertThat(pool.getIdleCount()).isEqualTo(0);
        }
    }

    @Test
    void requiteMakesEntryAvailableAgain() throws Exception {
        try (ConcurrentPool<TestPoolEntry> pool = new ConcurrentPool<>(config, factory)) {
            TestPoolEntry entry = borrowSync(pool, 100, TimeUnit.MILLISECONDS);
            assertThat(entry.getState()).isEqualTo(PoolEntry.STATE_IN_USE);

            pool.requite(entry);
            assertThat(entry.getState()).isEqualTo(PoolEntry.STATE_NOT_IN_USE);
            assertThat(pool.getActiveCount()).isEqualTo(0);
            assertThat(pool.getIdleCount()).isEqualTo(1);
        }
    }

    @Test
    void borrowReusesRequitedEntry() throws Exception {
        try (ConcurrentPool<TestPoolEntry> pool = new ConcurrentPool<>(config, factory)) {
            TestPoolEntry entry1 = borrowSync(pool, 100, TimeUnit.MILLISECONDS);
            pool.requite(entry1);

            TestPoolEntry entry2 = borrowSync(pool, 100, TimeUnit.MILLISECONDS);
            // 应复用同一条目（ThreadLocal 或共享列表）
            assertThat(entry2).isSameAs(entry1);
            assertThat(pool.getTotalCount()).isEqualTo(1);
        }
    }

    @Test
    void removeDecrementsCountAndClosesEntry() throws Exception {
        try (ConcurrentPool<TestPoolEntry> pool = new ConcurrentPool<>(config, factory)) {
            TestPoolEntry entry = borrowSync(pool, 100, TimeUnit.MILLISECONDS);
            assertThat(pool.getTotalCount()).isEqualTo(1);

            pool.remove(entry);
            assertThat(entry.getState()).isEqualTo(PoolEntry.STATE_REMOVED);
            assertThat(entry.isClosed()).isTrue();
            assertThat(pool.getTotalCount()).isEqualTo(0);
        }
    }

    @Test
    void borrowTimesOutWhenPoolIsFull() throws Exception {
        try (ConcurrentPool<TestPoolEntry> pool = new ConcurrentPool<>(config, factory)) {
            // 填满池
            borrowSync(pool, 100, TimeUnit.MILLISECONDS);
            borrowSync(pool, 100, TimeUnit.MILLISECONDS);
            borrowSync(pool, 100, TimeUnit.MILLISECONDS);
            assertThat(pool.getTotalCount()).isEqualTo(3);

            // 下一次 borrowAsync 应超时
            CompletableFuture<TestPoolEntry> future = pool.borrowAsync(50, TimeUnit.MILLISECONDS);
            ExecutionException ex = org.junit.jupiter.api.Assertions.assertThrows(
                    ExecutionException.class,
                    () -> future.get(500, TimeUnit.MILLISECONDS));
            assertThat(ex.getCause()).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Timeout");
        }
    }

    @Test
    void borrowFromClosedPoolThrows() {
        ConcurrentPool<TestPoolEntry> pool = new ConcurrentPool<>(config, factory);
        pool.close();

        CompletableFuture<TestPoolEntry> future = pool.borrowAsync(100, TimeUnit.MILLISECONDS);
        assertThat(future.isCompletedExceptionally()).isTrue();

        ExecutionException ex = org.junit.jupiter.api.Assertions.assertThrows(
                ExecutionException.class, future::get);
        assertThat(ex.getCause()).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("closed");
    }

    @Test
    void closeClosesAllEntries() throws Exception {
        ConcurrentPool<TestPoolEntry> pool = new ConcurrentPool<>(config, factory);
        TestPoolEntry e1 = borrowSync(pool, 100, TimeUnit.MILLISECONDS);
        TestPoolEntry e2 = borrowSync(pool, 100, TimeUnit.MILLISECONDS);
        pool.requite(e1);
        pool.requite(e2);

        pool.close();
        assertThat(e1.isClosed()).isTrue();
        assertThat(e2.isClosed()).isTrue();
    }

    @Test
    void idleEvictorRemovesExpiredEntries() throws Exception {
        config.setMaxIdleTimeSeconds(0); // 立即过期
        try (ConcurrentPool<TestPoolEntry> pool = new ConcurrentPool<>(config, factory)) {
            TestPoolEntry entry = borrowSync(pool, 100, TimeUnit.MILLISECONDS);
            pool.requite(entry);

            // 将最后访问时间设置到很久以前
            entry.setLastAccessTime(System.nanoTime() - TimeUnit.SECONDS.toNanos(10));

            IdleEvictor<TestPoolEntry> evictor = new IdleEvictor<>(pool);
            evictor.run();

            assertThat(entry.getState()).isEqualTo(PoolEntry.STATE_REMOVED);
            assertThat(entry.isClosed()).isTrue();
            assertThat(pool.getTotalCount()).isEqualTo(0);
        }
    }

    @Test
    void idleEvictorDoesNotRemoveRecentEntries() throws Exception {
        config.setMaxIdleTimeSeconds(60);
        try (ConcurrentPool<TestPoolEntry> pool = new ConcurrentPool<>(config, factory)) {
            TestPoolEntry entry = borrowSync(pool, 100, TimeUnit.MILLISECONDS);
            pool.requite(entry);

            IdleEvictor<TestPoolEntry> evictor = new IdleEvictor<>(pool);
            evictor.run();

            assertThat(entry.getState()).isEqualTo(PoolEntry.STATE_NOT_IN_USE);
            assertThat(entry.isClosed()).isFalse();
            assertThat(pool.getTotalCount()).isEqualTo(1);
        }
    }

    @Test
    void idleEvictorDoesNotRemoveInUseEntries() throws Exception {
        config.setMaxIdleTimeSeconds(0);
        try (ConcurrentPool<TestPoolEntry> pool = new ConcurrentPool<>(config, factory)) {
            TestPoolEntry entry = borrowSync(pool, 100, TimeUnit.MILLISECONDS);
            // 条目处于 IN_USE 状态，设置较早的访问时间
            entry.setLastAccessTime(System.nanoTime() - TimeUnit.SECONDS.toNanos(10));

            IdleEvictor<TestPoolEntry> evictor = new IdleEvictor<>(pool);
            evictor.run();

            assertThat(entry.getState()).isEqualTo(PoolEntry.STATE_IN_USE);
            assertThat(entry.isClosed()).isFalse();
            assertThat(pool.getTotalCount()).isEqualTo(1);
        }
    }

    @Test
    void getActiveAndIdleCountsAreConsistent() throws Exception {
        try (ConcurrentPool<TestPoolEntry> pool = new ConcurrentPool<>(config, factory)) {
            TestPoolEntry e1 = borrowSync(pool, 100, TimeUnit.MILLISECONDS);
            borrowSync(pool, 100, TimeUnit.MILLISECONDS); // 第二个条目保持 IN_USE
            pool.requite(e1);

            assertThat(pool.getActiveCount()).isEqualTo(1);
            assertThat(pool.getIdleCount()).isEqualTo(1);
            assertThat(pool.getTotalCount()).isEqualTo(2);
            assertThat(pool.getActiveCount() + pool.getIdleCount())
                    .isEqualTo(pool.getTotalCount());
        }
    }

    // ---- borrowAsync 测试 ----

    @Test
    void borrowAsyncReturnsEntryImmediatelyWhenIdleExists() throws Exception {
        try (ConcurrentPool<TestPoolEntry> pool = new ConcurrentPool<>(config, factory)) {
            TestPoolEntry entry = borrowSync(pool, 100, TimeUnit.MILLISECONDS);
            pool.requite(entry);

            CompletableFuture<TestPoolEntry> future = pool.borrowAsync(100, TimeUnit.MILLISECONDS);
            assertThat(future.isDone()).isTrue();
            assertThat(future.get()).isSameAs(entry);
            assertThat(future.get().getState()).isEqualTo(PoolEntry.STATE_IN_USE);
        }
    }

    @Test
    void borrowAsyncHitsThreadLocalFastPath() throws Exception {
        // factory 返回永不完成的 future，若走了 ThreadLocal 快速路径则不会调用 factory
        PoolEntryFactory<TestPoolEntry> blockingFactory = () -> new CompletableFuture<>(); // 永不完成

        try (ConcurrentPool<TestPoolEntry> pool = new ConcurrentPool<>(config, blockingFactory)) {
            // 手动往 sharedList 加一个 entry
            TestPoolEntry entry = new TestPoolEntry();
            pool.getSharedList().add(entry);

            // borrowAsync 拿到 entry，再 requite，entry 进入当前线程 ThreadLocal 列表
            CompletableFuture<TestPoolEntry> firstBorrow = pool.borrowAsync(100, TimeUnit.MILLISECONDS);
            assertThat(firstBorrow.isDone()).isTrue();
            TestPoolEntry borrowed = firstBorrow.get();
            pool.requite(borrowed);

            // 清空 sharedList，堵死第二级扫描
            pool.getSharedList().clear();

            // borrowAsync：sharedList 为空，factory 永不完成，只有 ThreadLocal 快速路径能立即返回
            CompletableFuture<TestPoolEntry> future = pool.borrowAsync(100, TimeUnit.MILLISECONDS);
            assertThat(future.isDone()).isTrue();
            assertThat(future.get()).isSameAs(borrowed);
            assertThat(future.get().getState()).isEqualTo(PoolEntry.STATE_IN_USE);
        }
    }

    @Test
    void borrowAsyncCreatesNewEntryWhenPoolIsEmpty() throws Exception {
        try (ConcurrentPool<TestPoolEntry> pool = new ConcurrentPool<>(config, factory)) {
            CompletableFuture<TestPoolEntry> future = pool.borrowAsync(100, TimeUnit.MILLISECONDS);
            TestPoolEntry entry = future.get(500, TimeUnit.MILLISECONDS);

            assertThat(entry).isNotNull();
            assertThat(entry.getState()).isEqualTo(PoolEntry.STATE_IN_USE);
            assertThat(pool.getTotalCount()).isEqualTo(1);
        }
    }

    @Test
    void borrowAsyncCompletesWhenEntryIsRequited() throws Exception {
        try (ConcurrentPool<TestPoolEntry> pool = new ConcurrentPool<>(config, factory)) {
            // 填满池
            List<TestPoolEntry> entries = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                entries.add(borrowSync(pool, 100, TimeUnit.MILLISECONDS));
            }

            // 池满，borrowAsync 进入等待队列
            CompletableFuture<TestPoolEntry> future = pool.borrowAsync(500, TimeUnit.MILLISECONDS);
            assertThat(future.isDone()).isFalse();

            // 归还一个条目，future 应完成
            TestPoolEntry requited = entries.get(0);
            pool.requite(requited);

            TestPoolEntry acquired = future.get(500, TimeUnit.MILLISECONDS);
            assertThat(acquired).isSameAs(requited);
            assertThat(acquired.getState()).isEqualTo(PoolEntry.STATE_IN_USE);
        }
    }

    @Test
    void borrowAsyncTimesOutWhenPoolIsFullAndNoRequite() throws Exception {
        try (ConcurrentPool<TestPoolEntry> pool = new ConcurrentPool<>(config, factory)) {
            // 填满池
            for (int i = 0; i < 3; i++) {
                borrowSync(pool, 100, TimeUnit.MILLISECONDS);
            }

            CompletableFuture<TestPoolEntry> future = pool.borrowAsync(100, TimeUnit.MILLISECONDS);

            ExecutionException ex = org.junit.jupiter.api.Assertions.assertThrows(
                    ExecutionException.class,
                    () -> future.get(500, TimeUnit.MILLISECONDS));
            assertThat(ex.getCause()).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Timeout");
        }
    }

    @Test
    void borrowAsyncFromClosedPoolReturnsFailed() {
        ConcurrentPool<TestPoolEntry> pool = new ConcurrentPool<>(config, factory);
        pool.close();

        CompletableFuture<TestPoolEntry> future = pool.borrowAsync(100, TimeUnit.MILLISECONDS);
        assertThat(future.isCompletedExceptionally()).isTrue();

        ExecutionException ex = org.junit.jupiter.api.Assertions.assertThrows(
                ExecutionException.class, future::get);
        assertThat(ex.getCause()).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("closed");
    }

    @Test
    void borrowAsyncCancelledWhenPoolClosedWhileWaiting() throws Exception {
        ConcurrentPool<TestPoolEntry> pool = new ConcurrentPool<>(config, factory);
        // 填满池
        for (int i = 0; i < 3; i++) {
            borrowSync(pool, 100, TimeUnit.MILLISECONDS);
        }

        CompletableFuture<TestPoolEntry> future = pool.borrowAsync(2000, TimeUnit.MILLISECONDS);
        assertThat(future.isDone()).isFalse();

        pool.close();

        ExecutionException ex = org.junit.jupiter.api.Assertions.assertThrows(
                ExecutionException.class,
                () -> future.get(500, TimeUnit.MILLISECONDS));
        assertThat(ex.getCause()).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("closed");
    }

    // ---- remove NOT_IN_USE 状态条目 ----

    @Test
    void removeIdleEntry_shouldRemoveFromPool() throws Exception {
        try (ConcurrentPool<TestPoolEntry> pool = new ConcurrentPool<>(config, factory)) {
            TestPoolEntry entry = borrowSync(pool, 100, TimeUnit.MILLISECONDS);
            pool.requite(entry);
            assertThat(pool.getTotalCount()).isEqualTo(1);

            // 直接 remove 空闲条目（NOT_IN_USE 状态）
            pool.remove(entry);
            assertThat(entry.getState()).isEqualTo(PoolEntry.STATE_REMOVED);
            assertThat(entry.isClosed()).isTrue();
            assertThat(pool.getTotalCount()).isEqualTo(0);
        }
    }

    // ---- requite 时等待者已超时 ----

    @Test
    void requite_whenWaiterAlreadyTimedOut_shouldNotLoseEntry() throws Exception {
        try (ConcurrentPool<TestPoolEntry> pool = new ConcurrentPool<>(config, factory)) {
            // 填满池
            List<TestPoolEntry> entries = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                entries.add(borrowSync(pool, 100, TimeUnit.MILLISECONDS));
            }

            // 发起一个极短超时的 borrowAsync，让它超时
            CompletableFuture<TestPoolEntry> timedOut = pool.borrowAsync(10, TimeUnit.MILLISECONDS);
            // 等待超时完成
            Thread.sleep(100);
            assertThat(timedOut.isCompletedExceptionally()).isTrue();

            // 归还条目，此时等待者已超时，requite 应正常处理不丢失条目
            pool.requite(entries.get(0));
            assertThat(pool.getIdleCount()).isGreaterThanOrEqualTo(1);
        }
    }

    // ---- getConfig ----

    @Test
    void getConfig_returnsDefensiveCopy() throws Exception {
        try (ConcurrentPool<TestPoolEntry> pool = new ConcurrentPool<>(config, factory)) {
            PoolConfig copy = pool.getConfig();
            assertThat(copy.getMaxPoolSize()).isEqualTo(config.getMaxPoolSize());
            // 修改返回的拷贝不影响池内部配置
            copy.setMaxPoolSize(999);
            assertThat(pool.getConfig().getMaxPoolSize()).isEqualTo(config.getMaxPoolSize());
        }
    }

    // ---- borrowAsync 异步创建失败直接返回原始异常 ----

    @Test
    void borrowAsync_whenAsyncCreateFails_shouldPropagateCreateFailure() throws Exception {
        PoolEntryFactory<TestPoolEntry> asyncFailFactory =
                () -> CompletableFuture.failedFuture(new RuntimeException("async create failed"));

        try (ConcurrentPool<TestPoolEntry> pool = new ConcurrentPool<>(config, asyncFailFactory)) {
            // borrowAsync 触发异步创建失败，应直接返回创建异常而非池等待超时
            CompletableFuture<TestPoolEntry> future = pool.borrowAsync(100, TimeUnit.MILLISECONDS);

            ExecutionException ex = org.junit.jupiter.api.Assertions.assertThrows(
                    ExecutionException.class,
                    () -> future.get(500, TimeUnit.MILLISECONDS));
            assertThat(ex.getCause()).isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("async create failed");
        }
    }

    @Test
    void borrowAsync_whenAsyncCreateFailsButPoolHasEntry_shouldWaitForRequite()
            throws Exception {
        config.setMaxPoolSize(2);
        AtomicInteger createCount = new AtomicInteger();
        PoolEntryFactory<TestPoolEntry> flakyFactory = () -> {
            if (createCount.incrementAndGet() == 1) {
                return CompletableFuture.completedFuture(new TestPoolEntry());
            }
            return CompletableFuture.failedFuture(new RuntimeException("async create failed"));
        };

        try (ConcurrentPool<TestPoolEntry> pool = new ConcurrentPool<>(config, flakyFactory)) {
            TestPoolEntry held = borrowSync(pool, 100, TimeUnit.MILLISECONDS);
            CompletableFuture<TestPoolEntry> waiter = pool.borrowAsync(200, TimeUnit.MILLISECONDS);

            pool.requite(held);

            TestPoolEntry acquired = waiter.get(500, TimeUnit.MILLISECONDS);
            assertThat(acquired).isSameAs(held);
        }
    }

    @Test
    void borrowAsyncWhenCancelledByCaller_shouldNotConsumeRequitedEntry() throws Exception {
        try (ConcurrentPool<TestPoolEntry> pool = new ConcurrentPool<>(config, factory)) {
            List<TestPoolEntry> entries = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                entries.add(borrowSync(pool, 100, TimeUnit.MILLISECONDS));
            }

            CompletableFuture<TestPoolEntry> cancelled = pool.borrowAsync(500, TimeUnit.MILLISECONDS);
            assertThat(cancelled.cancel(true)).isTrue();
            assertThat(cancelled.isCancelled()).isTrue();

            pool.requite(entries.get(0));
            CompletableFuture<TestPoolEntry> next = pool.borrowAsync(200, TimeUnit.MILLISECONDS);
            TestPoolEntry acquired = next.get(500, TimeUnit.MILLISECONDS);
            assertThat(acquired).isNotNull();
            assertThat(acquired.getState()).isEqualTo(PoolEntry.STATE_IN_USE);
        }
    }

    @Test
    void borrowAsyncTimeoutRacesWithRequite_shouldNotLoseEntry() throws Exception {
        try (ConcurrentPool<TestPoolEntry> pool = new ConcurrentPool<>(config, factory)) {
            TestPoolEntry held = borrowSync(pool, 100, TimeUnit.MILLISECONDS);
            CompletableFuture<TestPoolEntry> waiter = pool.borrowAsync(60, TimeUnit.MILLISECONDS);

            ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
            try {
                scheduler.schedule(() -> pool.requite(held), 55, TimeUnit.MILLISECONDS);
                try {
                    TestPoolEntry acquiredByWaiter = waiter.get(300, TimeUnit.MILLISECONDS);
                    assertThat(acquiredByWaiter.getState()).isEqualTo(PoolEntry.STATE_IN_USE);
                    pool.requite(acquiredByWaiter);
                } catch (ExecutionException ex) {
                    assertThat(ex.getCause()).isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("Timeout");
                }
            } finally {
                scheduler.shutdownNow();
            }

            TestPoolEntry reBorrowed = borrowSync(pool, 500, TimeUnit.MILLISECONDS);
            assertThat(reBorrowed).isNotNull();
            pool.requite(reBorrowed);
        }
    }

    @Test
    void closeBeforeAsyncCreateCompletion_shouldCloseEntryAndRollbackCount() throws Exception {
        CompletableFuture<TestPoolEntry> createFuture = new CompletableFuture<>();
        AtomicReference<TestPoolEntry> createdRef = new AtomicReference<>();
        PoolEntryFactory<TestPoolEntry> delayedFactory = () -> createFuture;

        ConcurrentPool<TestPoolEntry> pool = new ConcurrentPool<>(config, delayedFactory);
        CompletableFuture<TestPoolEntry> borrowed = pool.borrowAsync(500, TimeUnit.MILLISECONDS);
        pool.close();

        TestPoolEntry lateEntry = new TestPoolEntry();
        createdRef.set(lateEntry);
        createFuture.complete(lateEntry);

        ExecutionException ex = org.junit.jupiter.api.Assertions.assertThrows(
                ExecutionException.class,
                () -> borrowed.get(500, TimeUnit.MILLISECONDS));
        assertThat(ex.getCause()).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("closed");
        assertThat(createdRef.get().isClosed()).isTrue();
        assertThat(pool.getTotalCount()).isEqualTo(0);
    }

    @Test
    void borrowAsyncWhenCasConflictsBelowMaxPoolSize_shouldStillCreateWithoutWaiting()
            throws Exception {
        int workers = 16;
        config.setMaxPoolSize(workers);
        config.setConnectionTimeoutMillis(200);

        try (ConcurrentPool<TestPoolEntry> pool = new ConcurrentPool<>(config, factory)) {
            ExecutorService executor = Executors.newFixedThreadPool(workers);
            CyclicBarrier barrier = new CyclicBarrier(workers);
            try {
                List<Future<CompletableFuture<TestPoolEntry>>> submitted =
                        new ArrayList<>();
                for (int i = 0; i < workers; i++) {
                    submitted.add(executor.submit(() -> {
                        barrier.await();
                        return pool.borrowAsync(150, TimeUnit.MILLISECONDS);
                    }));
                }

                List<CompletableFuture<TestPoolEntry>> futures = new ArrayList<>();
                for (Future<CompletableFuture<TestPoolEntry>> task : submitted) {
                    futures.add(task.get(1, TimeUnit.SECONDS));
                }

                List<TestPoolEntry> borrowedEntries = new ArrayList<>();
                for (CompletableFuture<TestPoolEntry> future : futures) {
                    TestPoolEntry entry = future.get(1, TimeUnit.SECONDS);
                    assertThat(entry).isNotNull();
                    borrowedEntries.add(entry);
                }

                // 所有借用都成功，说明 CAS 冲突场景下没有"首次失败即入等待并超时"。
                assertThat(borrowedEntries).hasSize(workers);
                for (TestPoolEntry entry : borrowedEntries) {
                    pool.requite(entry);
                }
            } finally {
                executor.shutdownNow();
            }
        }
    }

    @Test
    void borrowAsyncHighConcurrencyTimeoutCleanup_shouldNotLeavePermanentHangs() throws Exception {
        config.setMaxPoolSize(1);
        try (ConcurrentPool<TestPoolEntry> pool = new ConcurrentPool<>(config, factory)) {
            TestPoolEntry held = borrowSync(pool, 100, TimeUnit.MILLISECONDS);

            List<CompletableFuture<TestPoolEntry>> waiters = new ArrayList<>();
            for (int i = 0; i < 120; i++) {
                waiters.add(pool.borrowAsync(20, TimeUnit.MILLISECONDS));
            }

            for (CompletableFuture<TestPoolEntry> waiter : waiters) {
                try {
                    waiter.get(300, TimeUnit.MILLISECONDS);
                } catch (ExecutionException ex) {
                    assertThat(ex.getCause()).isInstanceOf(IllegalStateException.class);
                } catch (TimeoutException ex) {
                    throw new AssertionError("waiter should not hang permanently", ex);
                }
            }

            pool.requite(held);
            TestPoolEntry acquired = borrowSync(pool, 500, TimeUnit.MILLISECONDS);
            assertThat(acquired).isSameAs(held);
        }
    }

    @Test
    void borrowAsyncEvictsDeadEntryFromSharedList() throws Exception {
        try (ConcurrentPool<TestPoolEntry> pool = new ConcurrentPool<>(config, factory)) {
            TestPoolEntry entry = pool.borrowAsync(100, TimeUnit.MILLISECONDS)
                    .get(1, TimeUnit.SECONDS);
            pool.requite(entry);
            entry.setAlive(false);

            TestPoolEntry second = pool.borrowAsync(100, TimeUnit.MILLISECONDS)
                    .get(1, TimeUnit.SECONDS);

            assertThat(second).isNotSameAs(entry);
            assertThat(second.isAlive()).isTrue();
            assertThat(entry.isClosed()).isTrue();
        }
    }

    @Test
    void borrowAsyncEvictsDeadEntryFromThreadLocal() throws Exception {
        config.setThreadLocalCacheSize(4);
        try (ConcurrentPool<TestPoolEntry> pool = new ConcurrentPool<>(config, factory)) {
            // borrowAsync + requite 在同一线程，条目会进入 ThreadLocal 缓存
            TestPoolEntry entry = borrowSync(pool, 100, TimeUnit.MILLISECONDS);
            pool.requite(entry);
            entry.setAlive(false);

            TestPoolEntry second = borrowSync(pool, 100, TimeUnit.MILLISECONDS);

            assertThat(second).isNotSameAs(entry);
            assertThat(second.isAlive()).isTrue();
            assertThat(entry.isClosed()).isTrue();
        }
    }

    @Test
    void borrowAsyncEvictsIdleExpiredEntryFromSharedList() throws Exception {
        try (ConcurrentPool<TestPoolEntry> pool = new ConcurrentPool<>(config, factory)) {
            TestPoolEntry entry = borrowSync(pool, 100, TimeUnit.MILLISECONDS);
            pool.requite(entry);
            // 模拟空闲超时：将 lastAccessTime 设到很久以前
            entry.setLastAccessTime(System.nanoTime() - TimeUnit.SECONDS.toNanos(60));

            TestPoolEntry second = borrowSync(pool, 100, TimeUnit.MILLISECONDS);

            assertThat(second).isNotSameAs(entry);
            assertThat(entry.isClosed()).isTrue();
        }
    }

    @Test
    void borrowAsyncEvictsIdleExpiredEntryFromThreadLocal() throws Exception {
        config.setThreadLocalCacheSize(4);
        try (ConcurrentPool<TestPoolEntry> pool = new ConcurrentPool<>(config, factory)) {
            TestPoolEntry entry = borrowSync(pool, 100, TimeUnit.MILLISECONDS);
            pool.requite(entry);
            entry.setLastAccessTime(System.nanoTime() - TimeUnit.SECONDS.toNanos(60));

            TestPoolEntry second = borrowSync(pool, 100, TimeUnit.MILLISECONDS);

            assertThat(second).isNotSameAs(entry);
            assertThat(entry.isClosed()).isTrue();
        }
    }

    @Test
    void idleEvictorEvictsIdleExpiredEntries() throws Exception {
        // 用 ManualScheduler 手动触发 IdleEvictor，不依赖真实时间
        List<Runnable> scheduled = new ArrayList<>();
        ConcurrentPool.TimeoutScheduler manualScheduler =
                (task, timeout, unit) -> {
                    scheduled.add(task);
                    return () -> { };
                };
        try (ConcurrentPool<TestPoolEntry> pool = new ConcurrentPool<>(config, factory, manualScheduler)) {
            TestPoolEntry entry = borrowSync(pool, 100, TimeUnit.MILLISECONDS);
            pool.requite(entry);
            entry.setLastAccessTime(System.nanoTime() - TimeUnit.SECONDS.toNanos(60));

            // 手动触发 IdleEvictor（scheduled[0] 是初始调度的任务）
            assertThat(scheduled).isNotEmpty();
            scheduled.get(0).run();

            assertThat(entry.isClosed()).isTrue();
            assertThat(pool.getTotalCount()).isEqualTo(0);
        }
    }

    @Test
    void idleEvictorSkipsActiveAndNonExpiredEntries() throws Exception {
        List<Runnable> scheduled = new ArrayList<>();
        ConcurrentPool.TimeoutScheduler manualScheduler =
                (task, timeout, unit) -> {
                    scheduled.add(task);
                    return () -> { };
                };
        try (ConcurrentPool<TestPoolEntry> pool = new ConcurrentPool<>(config, factory, manualScheduler)) {
            TestPoolEntry active = borrowSync(pool, 100, TimeUnit.MILLISECONDS);
            TestPoolEntry idle = borrowSync(pool, 100, TimeUnit.MILLISECONDS);
            pool.requite(idle);
            // idle 未超时，active 正在使用中

            scheduled.get(0).run();

            assertThat(active.isClosed()).isFalse();
            assertThat(idle.isClosed()).isFalse();
            assertThat(pool.getTotalCount()).isEqualTo(2);
            pool.requite(active);
        }
    }

    @Test
    void idleEvictorDisabledWhenIdleTimeoutIsZero() {
        List<Runnable> scheduled = new ArrayList<>();
        ConcurrentPool.TimeoutScheduler manualScheduler =
                (task, timeout, unit) -> {
                    scheduled.add(task);
                    return () -> { };
                };
        config.setMaxIdleTimeSeconds(0);
        try (ConcurrentPool<TestPoolEntry> pool = new ConcurrentPool<>(config, factory, manualScheduler)) {
            assertThat(scheduled).isEmpty();
        }
    }
}
