package com.lei.gateway.perf.util;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.DoubleRange;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

/**
 * Feature: gatling-performance-test
 * Property 8: 重复测试统计不变量
 * Validates: 需求 21.2, 21.3
 */
class RepeatAggregatorPropertyTest {

    // Property 8：重复测试统计不变量
    // 对于任意 n 次重复测试的 PerfMetrics 列表（n >= 2），
    // aggregate() 返回的平均 RPS 应等于各次 RPS 的算术平均值（误差 < 0.01%）。
    @Property(tries = 100)
    void averageRpsEqualsArithmeticMean(
            @ForAll("rpsList") @Size(min = 2, max = 10) List<Double> rpsList) {
        List<PerfMetrics> metrics = rpsList.stream()
                .map(rps -> new PerfMetrics(rps, 10L, 15L, 0.001, 0.0, 0L, null, Instant.now()))
                .toList();

        PerfMetrics result = RepeatAggregator.aggregate(metrics, 1.0); // 阈值设大，不触发警告

        double expectedAvg = rpsList.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        assertThat(result.rps()).isCloseTo(expectedAvg, offset(expectedAvg * 0.0001));
    }

    // 当各次 RPS 中最大值与最小值之差超过平均值的 deviationThreshold 时，
    // aggregate() 应正常返回（警告通过 stdout 输出，不抛异常）。
    @Property(tries = 100)
    void aggregateDoesNotThrowWhenDeviationExceedsThreshold(
            @ForAll @DoubleRange(min = 1000, max = 10000) double baseRps,
            @ForAll @DoubleRange(min = 0.01, max = 0.3) double deviationThreshold) {
        // 构造偏差超过阈值的数据：一个高值一个低值
        double highRps = baseRps * (1 + deviationThreshold + 0.05);
        double lowRps = baseRps * (1 - deviationThreshold - 0.05);
        List<PerfMetrics> metrics = List.of(
                new PerfMetrics(highRps, 10L, 15L, 0.001, 0.0, 0L, null, Instant.now()),
                new PerfMetrics(lowRps, 10L, 15L, 0.001, 0.0, 0L, null, Instant.now())
        );

        // 不抛异常
        PerfMetrics result = RepeatAggregator.aggregate(metrics, deviationThreshold);
        assertThat(result).isNotNull();
        assertThat(result.rps()).isGreaterThan(0);
    }

    @Provide
    Arbitrary<List<Double>> rpsList() {
        return Arbitraries.doubles().between(100, 50000).list().ofMinSize(2).ofMaxSize(10);
    }
}
