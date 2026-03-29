package com.lei.gateway.perf.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 采集宿主机环境快照，写入 target/gatling/env-snapshot.json。
 * 用于记录测试时的系统状态，区分性能差异是真实退化还是环境抖动。
 */
public final class EnvSnapshot {

    private static final String SNAPSHOT_FILE = "target/gatling/env-snapshot.json";
    private static final double LOAD_WARNING_RATIO = 0.80;
    private static final String GATLING_VERSION = "3.10.5";

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private EnvSnapshot() {
    }

    /**
     * 采集并写入环境快照，返回快照对象供 BaselineManager 存储摘要。
     *
     * @return 当前环境快照
     */
    public static EnvSnapshotData capture() {
        Runtime runtime = Runtime.getRuntime();
        OperatingSystemMXBean osMxBean = ManagementFactory.getOperatingSystemMXBean();

        int cpuCores = runtime.availableProcessors();
        long availableMemoryMb = runtime.maxMemory() / (1024 * 1024);
        double loadAvg1min = osMxBean.getSystemLoadAverage();
        // JDK 标准 API 只提供 1min load average；5min/15min 在 macOS/Linux 上不可直接获取
        double loadAvg5min = loadAvg1min;
        double loadAvg15min = loadAvg1min;

        String osName = osMxBean.getName();
        String osVersion = osMxBean.getVersion();
        String jvmVersion = System.getProperty("java.version", "unknown");
        String jvmArgs = String.join(" ", ManagementFactory.getRuntimeMXBean().getInputArguments());

        EnvSnapshotData snapshot = new EnvSnapshotData(
                cpuCores, availableMemoryMb,
                loadAvg1min, loadAvg5min, loadAvg15min,
                osName, osVersion, jvmVersion, jvmArgs,
                GATLING_VERSION, Instant.now()
        );

        writeSnapshot(snapshot, false);
        checkLoadWarning(snapshot);
        return snapshot;
    }

    /**
     * 检查 load average 是否超过 CPU 核心数的 80%，超过则输出警告。
     *
     * @param snapshot 环境快照
     */
    public static void checkLoadWarning(EnvSnapshotData snapshot) {
        double threshold = snapshot.cpuCores() * LOAD_WARNING_RATIO;
        if (snapshot.loadAvg1min() > threshold) {
            System.out.printf("警告：宿主机 load average（%.2f）超过 CPU 核心数（%d）的 80%%，"
                    + "测试结果可能受环境干扰%n",
                    snapshot.loadAvg1min(), snapshot.cpuCores());
        }
    }

    /**
     * 测试结束时再次采集 load average，与 start 对比；
     * 超过阈值时输出警告；将结束时快照追加写入 env-snapshot.json。
     *
     * @param start 测试开始时的快照
     */
    public static void captureEnd(EnvSnapshotData start) {
        OperatingSystemMXBean osMxBean = ManagementFactory.getOperatingSystemMXBean();
        double endLoad = osMxBean.getSystemLoadAverage();

        EnvSnapshotData endSnapshot = new EnvSnapshotData(
                start.cpuCores(), start.availableMemoryMb(),
                endLoad, endLoad, endLoad,
                start.osName(), start.osVersion(),
                start.jvmVersion(), start.jvmArgs(),
                start.gatlingVersion(), Instant.now()
        );

        double threshold = start.cpuCores() * LOAD_WARNING_RATIO;
        if (endLoad > threshold) {
            System.out.println("宿主机负载过高，测试结果可能受环境干扰，建议重新测试");
        }

        writeSnapshot(endSnapshot, true);
    }

    private static void writeSnapshot(EnvSnapshotData snapshot, boolean append) {
        try {
            Path path = Path.of(SNAPSHOT_FILE);
            Files.createDirectories(path.getParent());
            String json = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(snapshot);
            if (append) {
                Files.writeString(path, "\n" + json, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } else {
                Files.writeString(path, json, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            }
        } catch (IOException ex) {
            System.err.println("写入 env-snapshot.json 失败: " + ex.getMessage());
        }
    }
}
