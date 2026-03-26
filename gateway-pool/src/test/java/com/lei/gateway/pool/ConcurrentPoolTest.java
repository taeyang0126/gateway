package com.lei.gateway.pool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
        factory = TestPoolEntry::new;
    }

    @Test
    void borrowCreatesNewEntryWhenPoolIsEmpty() throws Exception {
        try (ConcurrentPool<TestPoolEntry> pool = new ConcurrentPool<>(config, factory)) {
            TestPoolEntry entry = pool.borrow(100, TimeUnit.MILLISECONDS);

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
            TestPoolEntry entry = pool.borrow(100, TimeUnit.MILLISECONDS);
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
            TestPoolEntry entry1 = pool.borrow(100, TimeUnit.MILLISECONDS);
            pool.requite(entry1);

            TestPoolEntry entry2 = pool.borrow(100, TimeUnit.MILLISECONDS);
            // 应复用同一条目（ThreadLocal 或共享列表）
            assertThat(entry2).isSameAs(entry1);
            assertThat(pool.getTotalCount()).isEqualTo(1);
        }
    }

    @Test
    void removeDecrementsCountAndClosesEntry() throws Exception {
        try (ConcurrentPool<TestPoolEntry> pool = new ConcurrentPool<>(config, factory)) {
            TestPoolEntry entry = pool.borrow(100, TimeUnit.MILLISECONDS);
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
            pool.borrow(100, TimeUnit.MILLISECONDS);
            pool.borrow(100, TimeUnit.MILLISECONDS);
            pool.borrow(100, TimeUnit.MILLISECONDS);
            assertThat(pool.getTotalCount()).isEqualTo(3);

            // 下一次 borrow 应超时
            assertThatThrownBy(() -> pool.borrow(50, TimeUnit.MILLISECONDS))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Timeout");
        }
    }

    @Test
    void borrowFromClosedPoolThrows() throws Exception {
        ConcurrentPool<TestPoolEntry> pool = new ConcurrentPool<>(config, factory);
        pool.close();

        assertThatThrownBy(() -> pool.borrow(100, TimeUnit.MILLISECONDS))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("closed");
    }

    @Test
    void closeClosesAllEntries() throws Exception {
        ConcurrentPool<TestPoolEntry> pool = new ConcurrentPool<>(config, factory);
        TestPoolEntry e1 = pool.borrow(100, TimeUnit.MILLISECONDS);
        TestPoolEntry e2 = pool.borrow(100, TimeUnit.MILLISECONDS);
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
            TestPoolEntry entry = pool.borrow(100, TimeUnit.MILLISECONDS);
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
            TestPoolEntry entry = pool.borrow(100, TimeUnit.MILLISECONDS);
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
            TestPoolEntry entry = pool.borrow(100, TimeUnit.MILLISECONDS);
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
            TestPoolEntry e1 = pool.borrow(100, TimeUnit.MILLISECONDS);
            TestPoolEntry e2 = pool.borrow(100, TimeUnit.MILLISECONDS);
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
            TestPoolEntry entry = pool.borrow(100, TimeUnit.MILLISECONDS);
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
        PoolEntryFactory<TestPoolEntry> blockingFactory = new PoolEntryFactory<>() {
            @Override
            public TestPoolEntry create() {
                throw new UnsupportedOperationException();
            }

            @Override
            public CompletableFuture<TestPoolEntry> createAsync() {
                return new CompletableFuture<>(); // 永不完成
            }
        };

        try (ConcurrentPool<TestPoolEntry> pool = new ConcurrentPool<>(config, blockingFactory)) {
            // 先用同步 borrow 创建一个 entry（走 factory.create 同步路径）
            // 但 blockingFactory.create 会抛异常，所以直接手动往 sharedList 加一个 entry
            TestPoolEntry entry = new TestPoolEntry();
            pool.getSharedList().add(entry);

            // borrow 拿到 entry，再 requite，entry 进入当前线程 ThreadLocal 列表
            TestPoolEntry borrowed = pool.borrow(100, TimeUnit.MILLISECONDS);
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
                entries.add(pool.borrow(100, TimeUnit.MILLISECONDS));
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
                pool.borrow(100, TimeUnit.MILLISECONDS);
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
    void borrowAsyncFromClosedPoolReturnsFailed() throws Exception {
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
            pool.borrow(100, TimeUnit.MILLISECONDS);
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

    // ---- borrow handoff 路径 ----

    @Test
    void borrowBlocksUntilEntryRequited() throws Exception {
        try (ConcurrentPool<TestPoolEntry> pool = new ConcurrentPool<>(config, factory)) {
            // 填满池
            List<TestPoolEntry> entries = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                entries.add(pool.borrow(100, TimeUnit.MILLISECONDS));
            }

            // 另一个线程延迟归还
            CompletableFuture.runAsync(() -> {
                try {
                    Thread.sleep(50);
                    pool.requite(entries.get(0));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });

            // borrow 应阻塞等待，然后拿到归还的条目
            TestPoolEntry acquired = pool.borrow(500, TimeUnit.MILLISECONDS);
            assertThat(acquired).isSameAs(entries.get(0));
            assertThat(acquired.getState()).isEqualTo(PoolEntry.STATE_IN_USE);
        }
    }

    // ---- tryCreateEntry 工厂异常路径 ----

    @Test
    void borrowWhenFactoryThrows_fallsBackToHandoff() throws Exception {
        PoolEntryFactory<TestPoolEntry> failFactory = new PoolEntryFactory<>() {
            private int callCount = 0;

            @Override
            public TestPoolEntry create() throws Exception {
                if (callCount++ == 0) {
                    throw new RuntimeException("factory error");
                }
                return new TestPoolEntry();
            }
        };

        try (ConcurrentPool<TestPoolEntry> pool = new ConcurrentPool<>(config, failFactory)) {
            // 第一次 borrow：工厂抛异常，tryCreateEntry 返回 null，进入 handoff 等待
            // 另一个线程创建并归还一个条目
            CompletableFuture.runAsync(() -> {
                try {
                    Thread.sleep(50);
                    TestPoolEntry entry = new TestPoolEntry();
                    pool.getSharedList().add(entry);
                    pool.getSharedList(); // 触发 sharedList 可见
                    // 直接通过 handoffQueue 传递
                    entry.compareAndSet(PoolEntry.STATE_NOT_IN_USE, PoolEntry.STATE_NOT_IN_USE);
                    pool.requite(entry); // requite 会尝试 handoff
                } catch (Exception e) {
                    Thread.currentThread().interrupt();
                }
            });

            // 工厂第一次失败，totalEntries 应回滚
            assertThat(pool.getTotalCount()).isEqualTo(0);
        }
    }

    // ---- remove NOT_IN_USE 状态条目 ----

    @Test
    void removeIdleEntry_shouldRemoveFromPool() throws Exception {
        try (ConcurrentPool<TestPoolEntry> pool = new ConcurrentPool<>(config, factory)) {
            TestPoolEntry entry = pool.borrow(100, TimeUnit.MILLISECONDS);
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
                entries.add(pool.borrow(100, TimeUnit.MILLISECONDS));
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

    // ---- borrowAsync 异步创建失败后进入等待队列 ----

    @Test
    void borrowAsync_whenAsyncCreateFails_enqueuesWaiter() throws Exception {
        PoolEntryFactory<TestPoolEntry> asyncFailFactory = new PoolEntryFactory<>() {
            @Override
            public TestPoolEntry create() throws Exception {
                return new TestPoolEntry();
            }

            @Override
            public CompletableFuture<TestPoolEntry> createAsync() {
                return CompletableFuture.failedFuture(
                        new RuntimeException("async create failed"));
            }
        };

        try (ConcurrentPool<TestPoolEntry> pool = new ConcurrentPool<>(config, asyncFailFactory)) {
            // borrowAsync 触发异步创建失败，进入等待队列，最终超时
            CompletableFuture<TestPoolEntry> future = pool.borrowAsync(100, TimeUnit.MILLISECONDS);

            ExecutionException ex = org.junit.jupiter.api.Assertions.assertThrows(
                    ExecutionException.class,
                    () -> future.get(500, TimeUnit.MILLISECONDS));
            assertThat(ex.getCause()).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void borrowAsyncWhenCancelledByCaller_shouldNotConsumeRequitedEntry() throws Exception {
        try (ConcurrentPool<TestPoolEntry> pool = new ConcurrentPool<>(config, factory)) {
            List<TestPoolEntry> entries = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                entries.add(pool.borrow(100, TimeUnit.MILLISECONDS));
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
            TestPoolEntry held = pool.borrow(100, TimeUnit.MILLISECONDS);
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

            TestPoolEntry reBorrowed = pool.borrow(500, TimeUnit.MILLISECONDS);
            assertThat(reBorrowed).isNotNull();
            pool.requite(reBorrowed);
        }
    }

    @Test
    void closeBeforeAsyncCreateCompletion_shouldCloseEntryAndRollbackCount() throws Exception {
        CompletableFuture<TestPoolEntry> createFuture = new CompletableFuture<>();
        AtomicReference<TestPoolEntry> createdRef = new AtomicReference<>();
        PoolEntryFactory<TestPoolEntry> delayedFactory = new PoolEntryFactory<>() {
            @Override
            public TestPoolEntry create() {
                throw new UnsupportedOperationException();
            }

            @Override
            public CompletableFuture<TestPoolEntry> createAsync() {
                return createFuture;
            }
        };

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

                List<TestPoolEntry> borrowed = new ArrayList<>();
                for (CompletableFuture<TestPoolEntry> future : futures) {
                    TestPoolEntry entry = future.get(1, TimeUnit.SECONDS);
                    assertThat(entry).isNotNull();
                    borrowed.add(entry);
                }

                // 所有借用都成功，说明 CAS 冲突场景下没有“首次失败即入等待并超时”。
                assertThat(borrowed).hasSize(workers);
                for (TestPoolEntry entry : borrowed) {
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
            TestPoolEntry held = pool.borrow(100, TimeUnit.MILLISECONDS);

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
            TestPoolEntry acquired = pool.borrow(500, TimeUnit.MILLISECONDS);
            assertThat(acquired).isSameAs(held);
        }
    }

    @Test
    void borrowEvictsDeadEntryFromSharedList() throws Exception {
        try (ConcurrentPool<TestPoolEntry> pool = new ConcurrentPool<>(config, factory)) {
            TestPoolEntry entry = pool.borrow(100, TimeUnit.MILLISECONDS);
            pool.requite(entry);
            entry.setAlive(false);

            TestPoolEntry second = pool.borrow(100, TimeUnit.MILLISECONDS);

            assertThat(second).isNotSameAs(entry);
            assertThat(second.isAlive()).isTrue();
            assertThat(entry.isClosed()).isTrue();
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
    void borrowEvictsDeadEntryFromThreadLocal() throws Exception {
        config.setThreadLocalCacheSize(4);
        try (ConcurrentPool<TestPoolEntry> pool = new ConcurrentPool<>(config, factory)) {
            // borrow + requite 在同一线程，条目会进入 ThreadLocal 缓存
            TestPoolEntry entry = pool.borrow(100, TimeUnit.MILLISECONDS);
            pool.requite(entry);
            entry.setAlive(false);

            TestPoolEntry second = pool.borrow(100, TimeUnit.MILLISECONDS);

            assertThat(second).isNotSameAs(entry);
            assertThat(second.isAlive()).isTrue();
            assertThat(entry.isClosed()).isTrue();
        }
    }
}
