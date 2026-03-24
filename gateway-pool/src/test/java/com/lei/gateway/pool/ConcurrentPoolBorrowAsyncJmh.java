package com.lei.gateway.pool;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

/**
 * ConcurrentPool 同步/异步 borrow 热路径基准。
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 5, time = 2, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 10, time = 2, timeUnit = TimeUnit.SECONDS)
@Fork(3)
@Threads(16)
public class ConcurrentPoolBorrowAsyncJmh {

    @State(Scope.Benchmark)
    public static class PoolState {

        @Param({"32", "128"})
        int maxPoolSize;

        ConcurrentPool<TestPoolEntry> pool;

        @Setup(Level.Trial)
        public void setup() throws Exception {
            PoolConfig config = new PoolConfig();
            config.setMaxPoolSize(maxPoolSize);
            config.setConnectionTimeoutMillis(200);
            config.setThreadLocalCacheSize(32);
            pool = new ConcurrentPool<>(config, TestPoolEntry::new);

            // 预热：提前创建一批条目，避免基准阶段把建连成本混进去。
            List<TestPoolEntry> entries = new ArrayList<>(maxPoolSize);
            for (int i = 0; i < maxPoolSize; i++) {
                entries.add(pool.borrow(200, TimeUnit.MILLISECONDS));
            }
            for (TestPoolEntry entry : entries) {
                pool.requite(entry);
            }
        }

        @TearDown(Level.Trial)
        public void tearDown() {
            if (pool != null) {
                pool.close();
            }
        }
    }

    @State(Scope.Thread)
    public static class SaturatedPoolState {

        @Param({"32"})
        int maxPoolSize;

        @Param({"wheel", "executor"})
        String timeoutScheduler;

        @Param({"20"})
        int timeoutMillis;

        ConcurrentPool<TestPoolEntry> pool;
        List<TestPoolEntry> heldEntries;
        ExecutorTimeoutScheduler executorTimeoutScheduler;

        @Setup(Level.Trial)
        public void setup() throws Exception {
            PoolConfig config = new PoolConfig();
            config.setMaxPoolSize(maxPoolSize);
            config.setConnectionTimeoutMillis(500);
            config.setThreadLocalCacheSize(32);
            if ("executor".equals(timeoutScheduler)) {
                executorTimeoutScheduler = new ExecutorTimeoutScheduler();
                pool = new ConcurrentPool<>(config, TestPoolEntry::new,
                        executorTimeoutScheduler);
            } else {
                pool = new ConcurrentPool<>(config, TestPoolEntry::new);
            }

            heldEntries = new ArrayList<>(maxPoolSize);
            for (int i = 0; i < maxPoolSize; i++) {
                heldEntries.add(pool.borrow(200, TimeUnit.MILLISECONDS));
            }
        }

        @TearDown(Level.Invocation)
        public void restoreSaturatedState() throws Exception {
            // 触发一次 requite->borrow，可惰性清理已超时/已取消等待节点，并恢复池满状态。
            TestPoolEntry entry = heldEntries.get(0);
            pool.requite(entry);
            TestPoolEntry reBorrowed = pool.borrow(200, TimeUnit.MILLISECONDS);
            heldEntries.set(0, reBorrowed);
        }

        @TearDown(Level.Trial)
        public void tearDown() {
            if (pool != null) {
                pool.close();
            }
            if (executorTimeoutScheduler != null) {
                executorTimeoutScheduler.shutdown();
            }
        }
    }

    @Benchmark
    public void borrowAsyncAndRequite(PoolState state, Blackhole blackhole)
            throws Exception {
        CompletableFuture<TestPoolEntry> future =
                state.pool.borrowAsync(200, TimeUnit.MILLISECONDS);
        TestPoolEntry entry = future.get(1, TimeUnit.SECONDS);
        blackhole.consume(entry);
        state.pool.requite(entry);
    }

    @Benchmark
    public void borrowSyncAndRequite(PoolState state, Blackhole blackhole)
            throws Exception {
        TestPoolEntry entry = state.pool.borrow(200, TimeUnit.MILLISECONDS);
        blackhole.consume(entry);
        state.pool.requite(entry);
    }

    @Benchmark
    @Threads(1)
    public void borrowAsyncTimeoutWhenPoolFull(SaturatedPoolState state,
            Blackhole blackhole) throws Exception {
        CompletableFuture<TestPoolEntry> future =
                state.pool.borrowAsync(state.timeoutMillis, TimeUnit.MILLISECONDS);
        try {
            TestPoolEntry entry = future.get(2, TimeUnit.SECONDS);
            blackhole.consume(entry);
        } catch (ExecutionException ex) {
            blackhole.consume(ex.getCause());
        }
    }

    @Benchmark
    @Threads(1)
    public void borrowAsyncCancelWhenPoolFull(SaturatedPoolState state,
            Blackhole blackhole) {
        CompletableFuture<TestPoolEntry> future =
                state.pool.borrowAsync(500, TimeUnit.MILLISECONDS);
        boolean cancelled = future.cancel(false);
        blackhole.consume(cancelled);
    }

    /**
     * 本地快速运行入口：
     * mvn -pl gateway-pool -DskipTests test-compile
     * mvn -pl gateway-pool -Dexec.classpathScope=test \
     *   -Dexec.mainClass=org.openjdk.jmh.Main \
     *   -Dexec.args="ConcurrentPoolBorrowAsyncJmh -wi 1 -i 3 -f 1" \
     *   org.codehaus.mojo:exec-maven-plugin:3.5.0:java
     */
    public static void main(String[] args) throws RunnerException {
        Options options = new OptionsBuilder()
                .include(ConcurrentPoolBorrowAsyncJmh.class.getSimpleName())
                .build();
        new Runner(options).run();
    }

    static final class ExecutorTimeoutScheduler
            implements ConcurrentPool.TimeoutScheduler {
        private final ScheduledExecutorService scheduler =
                Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "pool-timeout-executor");
                    t.setDaemon(true);
                    return t;
                });

        @Override
        public ConcurrentPool.TimeoutHandle schedule(Runnable task, long timeout,
                TimeUnit unit) {
            ScheduledFuture<?> future = scheduler.schedule(task, timeout, unit);
            return () -> future.cancel(false);
        }

        void shutdown() {
            scheduler.shutdownNow();
        }
    }
}
