package com.lei.gateway.perf.util;

import java.time.Instant;
import java.util.List;

/**
 * 对多次重复测试结果取算术平均值，偏差超过阈值时输出警告。
 */
public final class RepeatAggregator {

    private RepeatAggregator() {
    }

    /**
     * 聚合多次 PerfMetrics，返回平均值。
     * repeatCount=1 时直接返回单次结果，不执行聚合。
     * 各次 RPS 偏差超过 deviationThreshold 时输出警告。
     *
     * @param results            多次测试结果列表
     * @param deviationThreshold 偏差阈值（如 0.10 表示 10%）
     * @return 聚合后的平均指标
     */
    public static PerfMetrics aggregate(List<PerfMetrics> results, double deviationThreshold) {
        if (results == null || results.isEmpty()) {
            throw new IllegalArgumentException("results 不能为空");
        }
        if (results.size() == 1) {
            return results.get(0);
        }

        double avgRps = results.stream().mapToDouble(PerfMetrics::rps).average().orElse(0);
        double avgP95 = results.stream().mapToLong(PerfMetrics::p95Ms).average().orElse(0);
        double avgP99 = results.stream().mapToLong(PerfMetrics::p99Ms).average().orElse(0);
        double avgErrorRate = results.stream().mapToDouble(PerfMetrics::errorRate).average().orElse(0);
        double avgMockRps = results.stream().mapToDouble(PerfMetrics::mockRps).average().orElse(0);
        double avgKneeRps = results.stream().mapToLong(PerfMetrics::kneeRps).average().orElse(0);

        // 偏差检测：最大值与最小值之差超过平均值 deviationThreshold 时输出警告
        double maxRps = results.stream().mapToDouble(PerfMetrics::rps).max().orElse(0);
        double minRps = results.stream().mapToDouble(PerfMetrics::rps).min().orElse(0);
        if (avgRps > 0 && (maxRps - minRps) / avgRps > deviationThreshold) {
            System.out.printf("结果波动较大，建议检查测试环境稳定性（RPS 偏差 %.1f%%，阈值 %.0f%%）%n",
                    (maxRps - minRps) / avgRps * 100, deviationThreshold * 100);
        }

        // env 和 timestamp 取最后一次的值
        PerfMetrics last = results.get(results.size() - 1);
        return new PerfMetrics(
                avgRps,
                Math.round(avgP95),
                Math.round(avgP99),
                avgErrorRate,
                avgMockRps,
                Math.round(avgKneeRps),
                last.env(),
                Instant.now()
        );
    }
}
