package com.lei.gateway.pool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class IdleEvictorTest {

    private PoolConfig config;
    private PoolEntryFactory<TestPoolEntry> factory;

    @BeforeEach
    void setUp() {
        config = new PoolConfig();
        config.setMaxPoolSize(5);
        config.setMaxIdleTimeSeconds(10);
        config.setConnectionTimeoutMillis(200);
        factory = () -> CompletableFuture.completedFuture(new TestPoolEntry());
    }

    @Test
    void evictsExpiredIdleEntry() throws Exception {
        try (ConcurrentPool<TestPoolEntry> pool = new ConcurrentPool<>(config, factory)) {
            TestPoolEntry entry = pool.borrowAsync(100, TimeUnit.MILLISECONDS)
                    .get(1, TimeUnit.SECONDS);
            pool.requite(entry);
            entry.setLastAccessTime(System.nanoTime() - TimeUnit.SECONDS.toNanos(60));

            new IdleEvictor<>(pool).run();

            assertThat(entry.isClosed()).isTrue();
            assertThat(pool.getTotalCount()).isEqualTo(0);
        }
    }

    @Test
    void skipsNonExpiredIdleEntry() throws Exception {
        try (ConcurrentPool<TestPoolEntry> pool = new ConcurrentPool<>(config, factory)) {
            TestPoolEntry entry = pool.borrowAsync(100, TimeUnit.MILLISECONDS)
                    .get(1, TimeUnit.SECONDS);
            pool.requite(entry);
            // lastAccessTime 刚刚更新，未超时

            new IdleEvictor<>(pool).run();

            assertThat(entry.isClosed()).isFalse();
            assertThat(pool.getTotalCount()).isEqualTo(1);
        }
    }

    @Test
    void skipsActiveEntry() throws Exception {
        try (ConcurrentPool<TestPoolEntry> pool = new ConcurrentPool<>(config, factory)) {
            TestPoolEntry entry = pool.borrowAsync(100, TimeUnit.MILLISECONDS)
                    .get(1, TimeUnit.SECONDS);
            // 不 requite，entry 处于 IN_USE 状态
            entry.setLastAccessTime(System.nanoTime() - TimeUnit.SECONDS.toNanos(60));

            new IdleEvictor<>(pool).run();

            assertThat(entry.isClosed()).isFalse();
            assertThat(pool.getTotalCount()).isEqualTo(1);
            pool.requite(entry);
        }
    }

    @Test
    void evictsOnlyExpiredAmongMultiple() throws Exception {
        try (ConcurrentPool<TestPoolEntry> pool = new ConcurrentPool<>(config, factory)) {
            TestPoolEntry expired = pool.borrowAsync(100, TimeUnit.MILLISECONDS)
                    .get(1, TimeUnit.SECONDS);
            TestPoolEntry fresh = pool.borrowAsync(100, TimeUnit.MILLISECONDS)
                    .get(1, TimeUnit.SECONDS);
            pool.requite(expired);
            pool.requite(fresh);
            expired.setLastAccessTime(System.nanoTime() - TimeUnit.SECONDS.toNanos(60));

            new IdleEvictor<>(pool).run();

            assertThat(expired.isClosed()).isTrue();
            assertThat(fresh.isClosed()).isFalse();
            assertThat(pool.getTotalCount()).isEqualTo(1);
        }
    }
}
