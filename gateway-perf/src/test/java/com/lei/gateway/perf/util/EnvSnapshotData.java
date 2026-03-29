package com.lei.gateway.perf.util;

import java.time.Instant;

/**
 * 宿主机环境快照，记录测试时的系统状态，用于区分性能差异是真实退化还是环境抖动。
 */
public record EnvSnapshotData(
        int cpuCores,
        long availableMemoryMb,
        double loadAvg1min,
        double loadAvg5min,
        double loadAvg15min,
        String osName,
        String osVersion,
        String jvmVersion,
        String jvmArgs,
        String gatlingVersion,
        Instant capturedAt
) {
}
