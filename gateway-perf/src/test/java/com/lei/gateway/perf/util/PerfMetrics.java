package com.lei.gateway.perf.util;

import java.time.Instant;

/**
 * 单次测试的核心性能指标，用于基线存储和退化检测。
 */
public record PerfMetrics(
        double rps,
        long p95Ms,
        long p99Ms,
        double errorRate,
        double mockRps,
        long kneeRps,
        EnvSnapshotSummary env,
        Instant timestamp
) {
}
