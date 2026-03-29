package com.lei.gateway.perf.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;

/**
 * 负责读写 baseline.json 和执行退化检测。
 * 支持三种分支：强制更新基线、初始基线创建、退化检测。
 */
public class BaselineManager {

    private static final String BASELINE_FILE = "baseline.json";
    private static final String REPORT_FILE = "target/gatling/regression-report.txt";

    private final ObjectMapper mapper;
    private final File baselineFile;

    /** 使用默认路径（gateway-perf/baseline.json）构造。 */
    public BaselineManager() {
        this(new File(BASELINE_FILE));
    }

    /** 使用指定文件路径构造，便于测试注入临时文件。 */
    public BaselineManager(File baselineFile) {
        this.baselineFile = baselineFile;
        this.mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    /**
     * 将本次测试指标写入 baseline.json。
     *
     * @param metrics 本次测试指标
     */
    public void save(PerfMetrics metrics) {
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(baselineFile, metrics);
        } catch (IOException ex) {
            throw new RuntimeException("写入 baseline.json 失败: " + baselineFile.getAbsolutePath(), ex);
        }
    }

    /**
     * 读取 baseline.json，文件不存在时返回 {@code Optional.empty()}。
     *
     * @return 基线指标，或 empty
     */
    public Optional<PerfMetrics> load() {
        if (!baselineFile.exists()) {
            return Optional.empty();
        }
        try {
            return Optional.of(mapper.readValue(baselineFile, PerfMetrics.class));
        } catch (IOException ex) {
            throw new RuntimeException("读取 baseline.json 失败: " + baselineFile.getAbsolutePath(), ex);
        }
    }

    /**
     * 执行退化检测。
     * WHEN RPS 退化超过 rpsThreshold 或 P99 退化超过 p99Threshold，返回退化结果。
     *
     * @param baseline     基线指标
     * @param current      本次指标
     * @param rpsThreshold RPS 退化阈值（如 0.10 表示 10%）
     * @param p99Threshold P99 退化阈值（如 0.20 表示 20%）
     * @return 退化检测结果
     */
    public RegressionResult compare(PerfMetrics baseline, PerfMetrics current,
            double rpsThreshold, double p99Threshold) {
        double rpsDeviation = (baseline.rps() - current.rps()) / baseline.rps();
        double p99Deviation = (current.p99Ms() - baseline.p99Ms()) / (double) baseline.p99Ms();

        boolean rpsRegressed = rpsDeviation > rpsThreshold;
        boolean p99Regressed = p99Deviation > p99Threshold;
        boolean regressed = rpsRegressed || p99Regressed;

        String rpsReport = rpsRegressed
                ? String.format("RPS 退化：基线 %.1f → 本次 %.1f，退化幅度 %.1f%%（阈值 %.0f%%）",
                        baseline.rps(), current.rps(), rpsDeviation * 100, rpsThreshold * 100)
                : null;

        String p99Report = p99Regressed
                ? String.format("P99 退化：基线 %dms → 本次 %dms，退化幅度 %.1f%%（阈值 %.0f%%）",
                        baseline.p99Ms(), current.p99Ms(), p99Deviation * 100, p99Threshold * 100)
                : null;

        return new RegressionResult(
                regressed,
                rpsReport,
                p99Report,
                baseline.rps(),
                current.rps(),
                rpsDeviation * 100,
                baseline.p99Ms(),
                current.p99Ms(),
                p99Deviation * 100
        );
    }

    /**
     * 将退化报告写入 target/gatling/regression-report.txt，同时打印到 stdout。
     *
     * @param result 退化检测结果
     */
    public void writeRegressionReport(RegressionResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== 性能退化报告 ===\n");
        sb.append(String.format("RPS：基线 %.1f → 本次 %.1f（退化 %.1f%%）%n",
                result.rpsBaselineValue(), result.rpsCurrentValue(), result.rpsDeviationPct()));
        sb.append(String.format("P99：基线 %dms → 本次 %dms（退化 %.1f%%）%n",
                result.p99BaselineMs(), result.p99CurrentMs(), result.p99DeviationPct()));
        if (result.rpsReport() != null) {
            sb.append(result.rpsReport()).append("\n");
        }
        if (result.p99Report() != null) {
            sb.append(result.p99Report()).append("\n");
        }
        String report = sb.toString();

        System.out.print(report);

        try {
            Path reportPath = Path.of(REPORT_FILE);
            Files.createDirectories(reportPath.getParent());
            Files.writeString(reportPath, report, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ex) {
            System.err.println("写入退化报告文件失败: " + ex.getMessage());
        }
    }

    /**
     * Simulation 调用入口，处理三种分支：
     * 1. updateBaseline=true → 强制覆盖并输出"基线已更新"
     * 2. baseline.json 不存在 → 写入初始基线并输出"初始基线已创建"
     * 3. 否则执行退化检测
     *
     * @param current          本次测试指标
     * @param updateBaseline   是否强制更新基线
     * @param rpsThreshold     RPS 退化阈值
     * @param p99Threshold     P99 退化阈值
     * @return 退化检测结果，强制更新或初始基线时返回 null
     */
    public RegressionResult handleBaseline(PerfMetrics current, boolean updateBaseline,
            double rpsThreshold, double p99Threshold) {
        if (updateBaseline) {
            save(current);
            System.out.println("基线已更新");
            return null;
        }

        Optional<PerfMetrics> existing = load();
        if (existing.isEmpty()) {
            save(current);
            System.out.println("初始基线已创建");
            return null;
        }

        RegressionResult result = compare(existing.get(), current, rpsThreshold, p99Threshold);
        if (result.regressed()) {
            writeRegressionReport(result);
        }
        return result;
    }
}
