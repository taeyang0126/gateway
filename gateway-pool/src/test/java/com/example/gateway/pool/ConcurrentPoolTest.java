package com.example.gateway.pool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
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
}
