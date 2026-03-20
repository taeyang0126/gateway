package com.example.gateway.pool;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;

/**
 * gateway-pool 模块的属性测试。
 */
class ConcurrentPoolPropertyTest {

    /**
     * Property 8: 池未满时，borrow 通过工厂创建新条目。
     */
    @Property(tries = 100)
    void borrowCreatesNewEntryWhenPoolNotFull(
            @ForAll @IntRange(min = 1, max = 50) int maxPoolSize) throws Exception {
        PoolConfig config = new PoolConfig();
        config.setMaxPoolSize(maxPoolSize);
        config.setConnectionTimeoutMillis(500);

        try (ConcurrentPool<TestPoolEntry> pool =
                new ConcurrentPool<>(config, TestPoolEntry::new)) {
            TestPoolEntry entry = pool.borrow(100, TimeUnit.MILLISECONDS);

            assertThat(entry).isNotNull();
            assertThat(entry.getState()).isEqualTo(PoolEntry.STATE_IN_USE);
            assertThat(pool.getTotalCount()).isEqualTo(1);
        }
    }

    /**
     * Property 9: 超过 maxIdleTimeSeconds 的空闲条目被清理。
     */
    @Property(tries = 100)
    void idleEvictorRemovesExpiredEntries(
            @ForAll @IntRange(min = 1, max = 300) int maxIdleTimeSeconds) throws Exception {
        PoolConfig config = new PoolConfig();
        config.setMaxPoolSize(10);
        config.setMaxIdleTimeSeconds(maxIdleTimeSeconds);

        try (ConcurrentPool<TestPoolEntry> pool =
                new ConcurrentPool<>(config, TestPoolEntry::new)) {
            TestPoolEntry entry = pool.borrow(100, TimeUnit.MILLISECONDS);
            pool.requite(entry);

            // 模拟空闲时间超过阈值
            long pastTime = System.nanoTime()
                    - TimeUnit.SECONDS.toNanos(maxIdleTimeSeconds + 1);
            entry.setLastAccessTime(pastTime);

            new IdleEvictor<>(pool).run();

            assertThat(entry.getState()).isEqualTo(PoolEntry.STATE_REMOVED);
            assertThat(entry.isClosed()).isTrue();
            assertThat(pool.getTotalCount()).isEqualTo(0);
        }
    }

    /**
     * Property 10: CAS 状态切换并发安全 — 同一条目不会被两个线程同时持有。
     */
    @Property(tries = 20)
    void casStateSwitchIsConcurrentSafe(
            @ForAll @IntRange(min = 2, max = 8) int threadCount) throws Exception {
        PoolConfig config = new PoolConfig();
        config.setMaxPoolSize(threadCount);
        config.setConnectionTimeoutMillis(2000);

        try (ConcurrentPool<TestPoolEntry> pool =
                new ConcurrentPool<>(config, TestPoolEntry::new)) {
            int iterations = 200;
            AtomicBoolean violation = new AtomicBoolean(false);
            Set<TestPoolEntry> inUseSet = ConcurrentHashMap.newKeySet();
            CyclicBarrier barrier = new CyclicBarrier(threadCount);

            List<Thread> threads = new ArrayList<>();
            for (int t = 0; t < threadCount; t++) {
                Thread thread = new Thread(() -> {
                    try {
                        barrier.await();
                        for (int i = 0; i < iterations; i++) {
                            TestPoolEntry entry =
                                    pool.borrow(1000, TimeUnit.MILLISECONDS);
                            // 如果条目已在 inUseSet 中，说明 CAS 安全性被破坏
                            if (!inUseSet.add(entry)) {
                                violation.set(true);
                            }
                            inUseSet.remove(entry);
                            pool.requite(entry);
                        }
                    } catch (Exception e) {
                        Thread.currentThread().interrupt();
                    }
                });
                threads.add(thread);
                thread.start();
            }

            for (Thread thread : threads) {
                thread.join(10_000);
            }

            assertThat(violation.get())
                    .as("同一条目被两个线程同时持有")
                    .isFalse();
        }
    }

    /**
     * Property 13: 连接池指标不变量 — activeCount + idleCount == totalCount。
     */
    @Property(tries = 100)
    void poolMetricsInvariant(
            @ForAll @IntRange(min = 1, max = 10) int borrowCount,
            @ForAll @IntRange(min = 0, max = 10) int requiteSeed,
            @ForAll @IntRange(min = 0, max = 5) int removeSeed) throws Exception {
        PoolConfig config = new PoolConfig();
        config.setMaxPoolSize(borrowCount);
        config.setConnectionTimeoutMillis(500);

        try (ConcurrentPool<TestPoolEntry> pool =
                new ConcurrentPool<>(config, TestPoolEntry::new)) {
            List<TestPoolEntry> borrowed = new ArrayList<>();
            for (int i = 0; i < borrowCount; i++) {
                borrowed.add(pool.borrow(200, TimeUnit.MILLISECONDS));
            }

            // 归还部分条目
            int reqCount = Math.min(requiteSeed, borrowed.size());
            for (int i = 0; i < reqCount; i++) {
                pool.requite(borrowed.get(i));
            }

            // 移除部分已归还的条目
            int rmCount = Math.min(removeSeed, reqCount);
            for (int i = 0; i < rmCount; i++) {
                pool.remove(borrowed.get(i));
            }

            assertThat(pool.getActiveCount() + pool.getIdleCount())
                    .as("activeCount + idleCount == totalCount")
                    .isEqualTo(pool.getTotalCount());
        }
    }
}
