package com.lei.gateway.proxy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;

class InFlightRequestTrackerPropertyTest {

    // Feature: graceful-shutdown, Property 2: 在途请求计数 round-trip
    @Property(tries = 100)
    void roundTripCountReturnsToZero(
            @ForAll @IntRange(min = 1, max = 500) int n) {

        InFlightRequestTracker tracker = new InFlightRequestTracker();

        for (int i = 0; i < n; i++) {
            tracker.increment();
        }
        assertThat(tracker.getInFlightCount()).isEqualTo(n);

        for (int i = 0; i < n; i++) {
            tracker.decrement();
        }
        assertThat(tracker.getInFlightCount()).isZero();
    }

    // Feature: graceful-shutdown, Property 3: 在途请求计数并发安全
    @Property(tries = 100)
    void concurrentRoundTripCountReturnsToZero(
            @ForAll @IntRange(min = 2, max = 16) int threadCount,
            @ForAll @IntRange(min = 1, max = 200) int opsPerThread)
            throws Exception {

        InFlightRequestTracker tracker = new InFlightRequestTracker();
        CyclicBarrier barrier = new CyclicBarrier(threadCount);

        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                threadCount, threadCount,
                0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>());

        try {
            List<Future<?>> futures = new ArrayList<>(threadCount);
            for (int t = 0; t < threadCount; t++) {
                futures.add(executor.submit(() -> {
                    try {
                        barrier.await(5, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                    for (int i = 0; i < opsPerThread; i++) {
                        tracker.increment();
                    }
                    for (int i = 0; i < opsPerThread; i++) {
                        tracker.decrement();
                    }
                }));
            }

            for (Future<?> f : futures) {
                f.get(10, TimeUnit.SECONDS);
            }

            assertThat(tracker.getInFlightCount()).isZero();
        } finally {
            executor.shutdown();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }
}
