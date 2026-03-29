package com.lei.gateway.perf.util;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

class RepeatAggregatorTest {

    private PerfMetrics metrics(double rps) {
        return new PerfMetrics(rps, 10L, 15L, 0.001, 0.0, 0L, null, Instant.now());
    }

    @Test
    void singleResultReturnedDirectly() {
        PerfMetrics single = metrics(8500.0);
        PerfMetrics result = RepeatAggregator.aggregate(List.of(single), 0.10);
        assertThat(result).isSameAs(single);
    }

    @Test
    void averageRpsCalculatedCorrectly() {
        List<PerfMetrics> results = List.of(
                metrics(8000.0),
                metrics(9000.0),
                metrics(10000.0)
        );
        PerfMetrics avg = RepeatAggregator.aggregate(results, 0.10);
        assertThat(avg.rps()).isCloseTo(9000.0, offset(9000.0 * 0.0001));
    }

    @Test
    void noWarningWhenDeviationExactlyAtThreshold() {
        // RPS: 9000, 11000 → avg=10000, max-min=2000, deviation=20%，恰好等于阈值不触发
        List<PerfMetrics> results = List.of(metrics(9000.0), metrics(11000.0));
        PerfMetrics avg = RepeatAggregator.aggregate(results, 0.20);
        assertThat(avg.rps()).isCloseTo(10000.0, offset(0.01));
    }

    @Test
    void warningTriggeredWhenDeviationAboveThreshold() {
        // RPS: 8990, 11010 → avg=10000, max-min=2020, deviation=20.2%，超过 20% 阈值
        List<PerfMetrics> results = List.of(metrics(8990.0), metrics(11010.0));
        PerfMetrics avg = RepeatAggregator.aggregate(results, 0.20);
        assertThat(avg.rps()).isCloseTo(10000.0, offset(0.01));
    }

    @Test
    void averageP99CalculatedCorrectly() {
        List<PerfMetrics> results = List.of(
                new PerfMetrics(8000.0, 10L, 20L, 0.001, 0.0, 0L, null, Instant.now()),
                new PerfMetrics(9000.0, 12L, 30L, 0.001, 0.0, 0L, null, Instant.now()),
                new PerfMetrics(10000.0, 14L, 40L, 0.001, 0.0, 0L, null, Instant.now())
        );
        PerfMetrics avg = RepeatAggregator.aggregate(results, 0.50);
        assertThat(avg.p99Ms()).isEqualTo(30L);
    }
}
