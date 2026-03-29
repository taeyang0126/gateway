package com.lei.gateway.perf.util;

/**
 * 退化检测结果，包含是否发生退化、退化幅度和报告文本。
 */
public record RegressionResult(
        boolean regressed,
        String rpsReport,
        String p99Report,
        double rpsBaselineValue,
        double rpsCurrentValue,
        double rpsDeviationPct,
        long p99BaselineMs,
        long p99CurrentMs,
        double p99DeviationPct
) {
}
