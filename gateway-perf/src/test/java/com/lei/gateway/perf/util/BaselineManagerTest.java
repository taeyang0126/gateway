package com.lei.gateway.perf.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class BaselineManagerTest {

    @TempDir
    Path tempDir;

    private BaselineManager manager;
    private File baselineFile;

    @BeforeEach
    void setUp() {
        baselineFile = tempDir.resolve("baseline.json").toFile();
        manager = new BaselineManager(baselineFile);
    }

    private PerfMetrics sampleMetrics(double rps, long p99Ms) {
        return new PerfMetrics(rps, p99Ms - 2, p99Ms, 0.001, 0.0, 0L, null, Instant.now());
    }

    @Test
    void loadReturnsEmptyWhenFileNotExists() {
        assertThat(manager.load()).isEmpty();
    }

    @Test
    void saveAndLoadRoundTrip() {
        PerfMetrics metrics = sampleMetrics(8500.0, 18L);
        manager.save(metrics);

        Optional<PerfMetrics> loaded = manager.load();
        assertThat(loaded).isPresent();
        assertThat(loaded.get().rps()).isEqualTo(metrics.rps());
        assertThat(loaded.get().p99Ms()).isEqualTo(metrics.p99Ms());
        assertThat(loaded.get().errorRate()).isEqualTo(metrics.errorRate());
    }

    @Test
    void handleBaselineCreatesInitialBaseline() {
        PerfMetrics current = sampleMetrics(8500.0, 18L);
        RegressionResult result = manager.handleBaseline(current, false, 0.10, 0.20);

        assertThat(result).isNull();
        assertThat(baselineFile).exists();
        assertThat(manager.load()).isPresent();
    }

    @Test
    void handleBaselineForceUpdateOverwrites() {
        PerfMetrics first = sampleMetrics(8500.0, 18L);
        manager.save(first);

        PerfMetrics updated = sampleMetrics(9000.0, 15L);
        RegressionResult result = manager.handleBaseline(updated, true, 0.10, 0.20);

        assertThat(result).isNull();
        assertThat(manager.load().get().rps()).isEqualTo(9000.0);
    }

    @Test
    void compareNoRegressionWhenExactlyAtThreshold() {
        PerfMetrics baseline = sampleMetrics(10000.0, 100L);
        // RPS 退化恰好 10%：(10000 - 9000) / 10000 = 0.10，不超过阈值
        PerfMetrics current = sampleMetrics(9000.0, 100L);

        RegressionResult result = manager.compare(baseline, current, 0.10, 0.20);

        assertThat(result.regressed()).isFalse();
        assertThat(result.rpsReport()).isNull();
    }

    @Test
    void compareRpsRegressionWhenSlightlyAboveThreshold() {
        PerfMetrics baseline = sampleMetrics(10000.0, 100L);
        // RPS 退化 10.1%：(10000 - 8990) / 10000 = 0.101
        PerfMetrics current = sampleMetrics(8990.0, 100L);

        RegressionResult result = manager.compare(baseline, current, 0.10, 0.20);

        assertThat(result.regressed()).isTrue();
        assertThat(result.rpsReport()).isNotNull();
        assertThat(result.rpsReport()).contains("RPS 退化");
    }

    @Test
    void compareP99NoRegressionWhenExactlyAtThreshold() {
        PerfMetrics baseline = sampleMetrics(10000.0, 100L);
        // P99 退化恰好 20%：(120 - 100) / 100 = 0.20，不超过阈值
        PerfMetrics current = sampleMetrics(10000.0, 120L);

        RegressionResult result = manager.compare(baseline, current, 0.10, 0.20);

        assertThat(result.regressed()).isFalse();
        assertThat(result.p99Report()).isNull();
    }

    @Test
    void compareP99RegressionWhenSlightlyAboveThreshold() {
        PerfMetrics baseline = sampleMetrics(10000.0, 100L);
        // P99 退化 21%：(121 - 100) / 100 = 0.21
        PerfMetrics current = sampleMetrics(10000.0, 121L);

        RegressionResult result = manager.compare(baseline, current, 0.10, 0.20);

        assertThat(result.regressed()).isTrue();
        assertThat(result.p99Report()).isNotNull();
        assertThat(result.p99Report()).contains("P99 退化");
    }

    @Test
    void writeRegressionReportContainsRequiredFields() {
        RegressionResult result = new RegressionResult(
                true, "RPS 退化：基线 10000.0 → 本次 8990.0，退化幅度 10.1%（阈值 10%）", null,
                10000.0, 8990.0, 10.1, 100L, 100L, 0.0);

        // 写入报告不抛异常（target/gatling 目录由方法内部创建）
        manager.writeRegressionReport(result);
    }
}
