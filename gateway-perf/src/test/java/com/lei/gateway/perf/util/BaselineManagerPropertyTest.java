package com.lei.gateway.perf.util;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.DoubleRange;
import net.jqwik.api.constraints.LongRange;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Feature: gatling-performance-test
 * Property 2: 基线序列化 round-trip
 * Property 3: 退化检测不变量（RPS + P99）
 * Property 4: 退化报告格式不变量
 */
class BaselineManagerPropertyTest {

    private File tempFile() throws IOException {
        return Files.createTempFile("baseline-", ".json").toFile();
    }

    // Property 2：基线序列化 round-trip
    // Validates: 需求 8.1, 17.3, 21.4, 22.3, 23.4
    @Property(tries = 100)
    void baselineRoundTrip(@ForAll("perfMetrics") PerfMetrics metrics) throws IOException {
        File file = tempFile();
        BaselineManager manager = new BaselineManager(file);

        manager.save(metrics);
        PerfMetrics loaded = manager.load().orElseThrow();

        assertThat(loaded.rps()).isEqualTo(metrics.rps());
        assertThat(loaded.p95Ms()).isEqualTo(metrics.p95Ms());
        assertThat(loaded.p99Ms()).isEqualTo(metrics.p99Ms());
        assertThat(loaded.errorRate()).isEqualTo(metrics.errorRate());
        assertThat(loaded.mockRps()).isEqualTo(metrics.mockRps());
        assertThat(loaded.kneeRps()).isEqualTo(metrics.kneeRps());
    }

    // Property 3：退化检测不变量（RPS + P99）
    // Validates: 需求 8.3, 15.1
    @Property(tries = 100)
    void rpsRegressionDetectedWhenAboveThreshold(
            @ForAll @DoubleRange(min = 1000, max = 50000) double baselineRps,
            @ForAll @DoubleRange(min = 0.01, max = 0.5) double rpsThreshold) throws IOException {
        // 退化幅度 = threshold + 0.01（略超阈值）
        double currentRps = baselineRps * (1 - rpsThreshold - 0.01);
        PerfMetrics baseline = metricsWithRps(baselineRps, 100L);
        PerfMetrics current = metricsWithRps(currentRps, 100L);

        File file = tempFile();
        RegressionResult result = new BaselineManager(file).compare(baseline, current, rpsThreshold, 0.99);

        assertThat(result.regressed()).isTrue();
        double expectedDeviation = (baselineRps - currentRps) / baselineRps * 100;
        assertThat(result.rpsDeviationPct()).isCloseTo(expectedDeviation, org.assertj.core.data.Offset.offset(0.001));
    }

    @Property(tries = 100)
    void rpsNoRegressionWhenAtOrBelowThreshold(
            @ForAll @DoubleRange(min = 1000, max = 50000) double baselineRps,
            @ForAll @DoubleRange(min = 0.05, max = 0.49) double rpsThreshold) throws IOException {
        // 退化幅度明显低于阈值（阈值 - 2%），确保不触发退化
        double currentRps = baselineRps * (1 - rpsThreshold + 0.02);
        PerfMetrics baseline = metricsWithRps(baselineRps, 100L);
        PerfMetrics current = metricsWithRps(currentRps, 100L);

        File file = tempFile();
        RegressionResult result = new BaselineManager(file).compare(baseline, current, rpsThreshold, 0.99);

        assertThat(result.regressed()).isFalse();
    }

    @Property(tries = 100)
    void p99RegressionDetectedWhenAboveThreshold(
            @ForAll @LongRange(min = 100, max = 5000) long baselineP99,
            @ForAll @DoubleRange(min = 0.05, max = 0.5) double p99Threshold) throws IOException {
        // P99 退化幅度 = threshold + 5%（明显超阈值，避免 long 截断导致退化幅度为 0）
        long currentP99 = (long) (baselineP99 * (1 + p99Threshold + 0.05)) + 1;
        PerfMetrics baseline = metricsWithRps(10000.0, baselineP99);
        PerfMetrics current = metricsWithRps(10000.0, currentP99);

        File file = tempFile();
        RegressionResult result = new BaselineManager(file).compare(baseline, current, 0.99, p99Threshold);

        assertThat(result.regressed()).isTrue();
        assertThat(result.p99Report()).isNotNull();
    }

    // Property 4：退化报告格式不变量
    // Validates: 需求 8.6, 15.2, 15.3
    @Property(tries = 100)
    void regressionReportContainsBaselineCurrentAndDeviation(
            @ForAll @DoubleRange(min = 1000, max = 50000) double baselineRps,
            @ForAll @LongRange(min = 10, max = 5000) long baselineP99) throws IOException {
        double currentRps = baselineRps * 0.8; // 退化 20%，超过默认 10% 阈值
        long currentP99 = (long) (baselineP99 * 1.3); // 退化 30%，超过默认 20% 阈值

        PerfMetrics baseline = metricsWithRps(baselineRps, baselineP99);
        PerfMetrics current = metricsWithRps(currentRps, currentP99);

        File file = tempFile();
        RegressionResult result = new BaselineManager(file).compare(baseline, current, 0.10, 0.20);

        assertThat(result.regressed()).isTrue();
        // 报告必须包含基线值、本次值、退化幅度
        assertThat(result.rpsReport()).contains("基线").contains("本次").contains("退化幅度");
        assertThat(result.p99Report()).contains("基线").contains("本次").contains("退化幅度");
        // 数值字段正确
        assertThat(result.rpsBaselineValue()).isEqualTo(baselineRps);
        assertThat(result.rpsCurrentValue()).isEqualTo(currentRps);
        assertThat(result.p99BaselineMs()).isEqualTo(baselineP99);
        assertThat(result.p99CurrentMs()).isEqualTo(currentP99);
    }

    @Provide
    Arbitrary<PerfMetrics> perfMetrics() {
        return Combinators.combine(
                Arbitraries.doubles().between(100, 50000),
                Arbitraries.longs().between(1, 10000),
                Arbitraries.longs().between(1, 10000),
                Arbitraries.doubles().between(0, 0.1),
                Arbitraries.doubles().between(0, 100000),
                Arbitraries.longs().between(0, 100000)
        ).as((rps, p95, p99, errorRate, mockRps, kneeRps) ->
                new PerfMetrics(rps, p95, p99, errorRate, mockRps, kneeRps, null, Instant.now())
        );
    }

    private PerfMetrics metricsWithRps(double rps, long p99Ms) {
        return new PerfMetrics(rps, p99Ms - 2, p99Ms, 0.001, 0.0, 0L, null, Instant.now());
    }
}
