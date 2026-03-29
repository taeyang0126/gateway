package com.lei.gateway.perf.util;

/**
 * 环境快照摘要，存储在 baseline.json 中，用于判断不同环境下基线对比的有效性。
 */
public record EnvSnapshotSummary(
        int cpuCores,
        String jvmVersion,
        long availableMemoryMb
) {
}
