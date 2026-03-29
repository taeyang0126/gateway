package com.lei.gateway.perf.util;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.LongRange;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Feature: gatling-performance-test, Property 10：膝点探测停止条件不变量。
 * 验证 KneeDetector 的停止条件在所有合法输入下均正确触发。
 */
class KneeDetectionPropertyTest {

    // ---- Property 10a：错误率超过阈值时必须停止 ----

    @Property(tries = 200)
    void stopWhenErrorRateExceedsThreshold(
            @ForAll @LongRange(min = 1, max = 10000) long prevP99,
            @ForAll @LongRange(min = 1, max = 10000) long currentP99,
            @ForAll("errorRateAboveThreshold") double errorRate) {
        // 错误率超过 1% 时，无论 P99 如何，都应停止
        assertThat(KneeDetector.shouldStop(prevP99, currentP99, errorRate)).isTrue();
    }

    // ---- Property 10b：P99 超过前一阶段 150% 时必须停止 ----

    @Property(tries = 200)
    void stopWhenP99SpikeExceedsRatio(
            @ForAll @LongRange(min = 100, max = 5000) long prevP99,
            @ForAll("errorRateBelowThreshold") double errorRate) {
        // currentP99 严格大于 prevP99 * 1.50，prevP99 >= 100 确保 long 截断后仍严格大于阈值
        // prevP99 * 1.51 - prevP99 * 1.50 = prevP99 * 0.01 >= 1（当 prevP99 >= 100 时）
        long currentP99 = (long) (prevP99 * 1.51);
        assertThat(KneeDetector.shouldStop(prevP99, currentP99, errorRate)).isTrue();
    }

    // ---- Property 10c：P99 未超过 150% 且错误率正常时不应停止 ----

    @Property(tries = 200)
    void noStopWhenBothConditionsNormal(
            @ForAll @LongRange(min = 1, max = 5000) long prevP99,
            @ForAll("errorRateBelowThreshold") double errorRate) {
        // currentP99 = prevP99 * 1.49（未超过 150% 阈值）
        long currentP99 = (long) (prevP99 * 1.49);
        // 确保 currentP99 >= 1
        if (currentP99 < 1) {
            currentP99 = 1;
        }
        assertThat(KneeDetector.shouldStop(prevP99, currentP99, errorRate)).isFalse();
    }

    // ---- Property 10d：首阶段（prevP99=0）不因 P99 触发停止 ----

    @Property(tries = 100)
    void firstStageNeverStopsOnP99Alone(
            @ForAll @LongRange(min = 1, max = 100000) long currentP99,
            @ForAll("errorRateBelowThreshold") double errorRate) {
        // prevP99=0 表示首阶段，不应因 P99 比较触发停止
        assertThat(KneeDetector.shouldStop(0, currentP99, errorRate)).isFalse();
    }

    // ---- Property 10e：findKneeIndex 在停止条件触发前持续递增 ----

    @Property(tries = 100)
    void kneeIndexIsFirstTriggerPoint(@ForAll("stageSequences") List<KneeDetector.StageMetrics> stages) {
        int kneeIdx = KneeDetector.findKneeIndex(stages);

        // 膝点之前的所有阶段均不应触发停止条件
        long prevP99 = 0;
        for (int ii = 0; ii < kneeIdx; ii++) {
            KneeDetector.StageMetrics stage = stages.get(ii);
            assertThat(KneeDetector.shouldStop(prevP99, stage.p99Ms(), stage.errorRate()))
                    .as("阶段 %d 不应触发停止条件", ii)
                    .isFalse();
            prevP99 = stage.p99Ms();
        }
    }

    // ---- Property 10f：findKneeIndex 返回值在合法范围内 ----

    @Property(tries = 100)
    void kneeIndexInBounds(@ForAll("stageSequences") List<KneeDetector.StageMetrics> stages) {
        int kneeIdx = KneeDetector.findKneeIndex(stages);
        assertThat(kneeIdx).isBetween(0, stages.size() - 1);
    }

    @Provide
    Arbitrary<Double> errorRateAboveThreshold() {
        // 错误率在 (0.011, 1.0] 范围内，使用整数映射避免 scale 问题
        return Arbitraries.integers().between(12, 1000)
                .map(ii -> ii / 1000.0);
    }

    @Provide
    Arbitrary<Double> errorRateBelowThreshold() {
        // 错误率在 [0.0, 0.009] 范围内，使用整数映射避免 scale 问题
        return Arbitraries.integers().between(0, 9)
                .map(ii -> ii / 1000.0);
    }

    @Provide
    Arbitrary<List<KneeDetector.StageMetrics>> stageSequences() {
        // 生成 3~10 个阶段，P99 单调递增（模拟负载递增时延迟上升），错误率低
        Arbitrary<Integer> stageCount = Arbitraries.integers().between(3, 10);
        return stageCount.flatMap(count -> {
            List<Arbitrary<KneeDetector.StageMetrics>> stageArbs = new ArrayList<>();
            for (int ii = 0; ii < count; ii++) {
                final int stageIndex = ii;
                // 错误率使用整数映射，避免 scale 问题
                Arbitrary<Double> safeErrorRate = Arbitraries.integers().between(0, 5)
                        .map(jj -> jj / 1000.0);
                Arbitrary<KneeDetector.StageMetrics> stageArb = Combinators.combine(
                        Arbitraries.doubles().between(100, 5000),
                        // P99 随阶段递增（基础值 + 阶段偏移）
                        Arbitraries.longs().between(
                                10L + stageIndex * 5L,
                                50L + stageIndex * 20L),
                        safeErrorRate
                ).as(KneeDetector.StageMetrics::new);
                stageArbs.add(stageArb);
            }
            return Arbitraries.of(stageArbs).list().ofSize(count)
                    .map(arbs -> arbs.stream()
                            .map(arb -> arb.sample())
                            .collect(java.util.stream.Collectors.toList()));
        });
    }
}
