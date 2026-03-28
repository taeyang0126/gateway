package com.lei.gateway.core.proxy;

import static org.assertj.core.api.Assertions.assertThat;

import io.netty.channel.embedded.EmbeddedChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;

class ChannelPoolEntryPropertyTest {

    private ChannelPoolEntry createEntry() {
        return new ChannelPoolEntry(new EmbeddedChannel(), "localhost:8080");
    }

    // Feature: upstream-h2-connection, Property 1: StreamId 不变量——始终为正奇数且严格递增
    @Property(tries = 100)
    void streamIdAlwaysPositiveOddAndStrictlyIncreasing(
            @ForAll @IntRange(min = 1, max = 2000) int callCount) {

        ChannelPoolEntry entry = createEntry();
        int previous = -1;

        for (int i = 0; i < callCount; i++) {
            int id = entry.nextStreamId();
            if (id == -1) {
                // 溢出后所有后续调用也必须返回 -1
                for (int j = 0; j < 10; j++) {
                    assertThat(entry.nextStreamId())
                            .as("溢出后第 %d 次调用应仍返回 -1", j)
                            .isEqualTo(-1);
                }
                return;
            }
            assertThat(id).as("stream ID 必须为正数").isGreaterThan(0);
            assertThat(id % 2).as("stream ID 必须为奇数: %d", id).isEqualTo(1);
            assertThat(id).as("stream ID 必须严格递增").isGreaterThan(previous);
            previous = id;
        }
    }

    // Feature: upstream-h2-connection, Property 1: 溢出不可恢复——一旦返回 -1 则永远返回 -1
    @Property(tries = 100)
    void overflowIsIrrecoverable(
            @ForAll @IntRange(min = 1, max = 50) int extraCalls) {

        ChannelPoolEntry entry = createEntry();

        // 将 streamIdGenerator 推进到接近 Integer.MAX_VALUE
        // MAX_VALUE = 2147483647，初始 -1，步长 2
        // 需要 (MAX_VALUE + 1) / 2 = 1073741824 次调用才溢出，直接用反射设置
        try {
            java.lang.reflect.Field field =
                    ChannelPoolEntry.class.getDeclaredField("streamIdGenerator");
            field.setAccessible(true);
            // 设为 MAX_VALUE - 2，下次 addAndGet(2) 得到 MAX_VALUE（最后一个有效值）
            ((java.util.concurrent.atomic.AtomicInteger) field.get(entry))
                    .set(Integer.MAX_VALUE - 2);
        } catch (ReflectiveOperationException ex) {
            throw new RuntimeException(ex);
        }

        // 最后一个有效 ID
        int lastValid = entry.nextStreamId();
        assertThat(lastValid).isEqualTo(Integer.MAX_VALUE);

        // 此后所有调用均应返回 -1
        for (int i = 0; i < extraCalls; i++) {
            assertThat(entry.nextStreamId())
                    .as("溢出后第 %d 次调用应返回 -1", i)
                    .isEqualTo(-1);
        }
    }

    // Feature: upstream-h2-connection, Property 1: 并发调用无重复且全为正奇数
    @Property(tries = 100)
    void concurrentStreamIdsAreUniquePositiveOdd(
            @ForAll @IntRange(min = 2, max = 16) int threadCount,
            @ForAll @IntRange(min = 1, max = 200) int idsPerThread)
            throws Exception {

        ChannelPoolEntry entry = createEntry();
        CyclicBarrier barrier = new CyclicBarrier(threadCount);
        Set<Integer> allIds = ConcurrentHashMap.newKeySet();

        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                threadCount, threadCount,
                0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>());

        try {
            List<Future<?>> futures = new ArrayList<>(threadCount);
            for (int th = 0; th < threadCount; th++) {
                futures.add(executor.submit(() -> {
                    try {
                        barrier.await(5, TimeUnit.SECONDS);
                    } catch (Exception ex) {
                        throw new RuntimeException(ex);
                    }
                    for (int i = 0; i < idsPerThread; i++) {
                        int id = entry.nextStreamId();
                        if (id == -1) {
                            return; // 溢出，停止
                        }
                        assertThat(id).as("stream ID 必须为正数").isGreaterThan(0);
                        assertThat(id % 2).as("stream ID 必须为奇数: %d", id).isEqualTo(1);
                        boolean added = allIds.add(id);
                        assertThat(added).as("stream ID 不得重复: %d", id).isTrue();
                    }
                }));
            }

            for (Future<?> fu : futures) {
                fu.get(10, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdown();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }
}
