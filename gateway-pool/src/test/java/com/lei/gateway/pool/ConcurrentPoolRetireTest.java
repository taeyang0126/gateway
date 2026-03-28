package com.lei.gateway.pool;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link ConcurrentPool#retire(PoolEntry)} 单元测试。
 *
 * <p>验证 retire 的 CAS 状态转换、从 sharedList/ThreadLocal 移除、totalEntries 减一、
 * 不调用 entry.close()、retire 后 entry 不会被 borrowAsync 获取到。
 */
class ConcurrentPoolRetireTest {

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

    private static TestPoolEntry borrowSync(ConcurrentPool<TestPoolEntry> pool) throws Exception {
        return pool.borrowAsync(100, TimeUnit.MILLISECONDS).get(300, TimeUnit.MILLISECONDS);
    }

    @Test
    void retireFromNotInUseState() throws Exception {
        try (ConcurrentPool<TestPoolEntry> pool = new ConcurrentPool<>(config, factory)) {
            TestPoolEntry entry = borrowSync(pool);
            pool.requite(entry);
            assertThat(entry.getState()).isEqualTo(PoolEntry.STATE_NOT_IN_USE);

            pool.retire(entry);

            assertThat(entry.getState()).isEqualTo(PoolEntry.STATE_REMOVED);
            assertThat(entry.isClosed()).isFalse();
            assertThat(pool.getTotalCount()).isZero();
            assertThat(pool.getSharedList()).doesNotContain(entry);
        }
    }

    @Test
    void retireFromInUseState() throws Exception {
        try (ConcurrentPool<TestPoolEntry> pool = new ConcurrentPool<>(config, factory)) {
            TestPoolEntry entry = borrowSync(pool);
            assertThat(entry.getState()).isEqualTo(PoolEntry.STATE_IN_USE);

            pool.retire(entry);

            assertThat(entry.getState()).isEqualTo(PoolEntry.STATE_REMOVED);
            assertThat(entry.isClosed()).isFalse();
            assertThat(pool.getTotalCount()).isZero();
            assertThat(pool.getSharedList()).doesNotContain(entry);
        }
    }

    @Test
    void retireDoesNotCloseEntry() throws Exception {
        try (ConcurrentPool<TestPoolEntry> pool = new ConcurrentPool<>(config, factory)) {
            TestPoolEntry entry = borrowSync(pool);
            pool.requite(entry);

            pool.retire(entry);

            assertThat(entry.isClosed()).isFalse();
            assertThat(entry.isAlive()).isTrue();
        }
    }

    @Test
    void removeClosesEntryButRetireDoesNot() throws Exception {
        try (ConcurrentPool<TestPoolEntry> pool = new ConcurrentPool<>(config, factory)) {
            TestPoolEntry entryToRemove = borrowSync(pool);
            TestPoolEntry entryToRetire = borrowSync(pool);

            pool.remove(entryToRemove);
            assertThat(entryToRemove.isClosed()).isTrue();

            pool.retire(entryToRetire);
            assertThat(entryToRetire.isClosed()).isFalse();
        }
    }

    @Test
    void retireDecrementsTotal() throws Exception {
        try (ConcurrentPool<TestPoolEntry> pool = new ConcurrentPool<>(config, factory)) {
            TestPoolEntry e1 = borrowSync(pool);
            TestPoolEntry e2 = borrowSync(pool);
            assertThat(pool.getTotalCount()).isEqualTo(2);

            pool.retire(e1);
            assertThat(pool.getTotalCount()).isEqualTo(1);

            pool.retire(e2);
            assertThat(pool.getTotalCount()).isZero();
        }
    }

    @Test
    void retiredEntryNotReturnedByBorrow() throws Exception {
        config.setMaxPoolSize(1);
        try (ConcurrentPool<TestPoolEntry> pool = new ConcurrentPool<>(config, factory)) {
            TestPoolEntry entry = borrowSync(pool);
            pool.requite(entry);

            pool.retire(entry);

            // borrow 应创建新 entry，不会拿到已 retire 的
            TestPoolEntry newEntry = borrowSync(pool);
            assertThat(newEntry).isNotSameAs(entry);
            assertThat(pool.getTotalCount()).isEqualTo(1);
        }
    }

    @Test
    void retireRemovedEntryIsNoop() throws Exception {
        try (ConcurrentPool<TestPoolEntry> pool = new ConcurrentPool<>(config, factory)) {
            TestPoolEntry entry = borrowSync(pool);
            pool.retire(entry);
            assertThat(pool.getTotalCount()).isZero();

            // 重复 retire 已 REMOVED 的 entry，应无副作用
            pool.retire(entry);
            assertThat(pool.getTotalCount()).isZero();
            assertThat(entry.isClosed()).isFalse();
        }
    }

    @Test
    void retireRemovesFromSharedList() throws Exception {
        try (ConcurrentPool<TestPoolEntry> pool = new ConcurrentPool<>(config, factory)) {
            TestPoolEntry e1 = borrowSync(pool);
            TestPoolEntry e2 = borrowSync(pool);
            pool.requite(e1);
            pool.requite(e2);
            assertThat(pool.getSharedList()).contains(e1, e2);

            pool.retire(e1);
            assertThat(pool.getSharedList()).doesNotContain(e1);
            assertThat(pool.getSharedList()).contains(e2);
        }
    }
}
