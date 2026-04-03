package com.lei.gateway.perf.simulation;

import com.lei.gateway.perf.util.BaselineManager;
import com.lei.gateway.perf.util.EnvSnapshot;
import com.lei.gateway.perf.util.EnvSnapshotData;
import com.lei.gateway.perf.util.EnvSnapshotSummary;
import com.lei.gateway.perf.util.PerfMetrics;
import io.gatling.javaapi.core.OpenInjectionStep;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static io.gatling.javaapi.core.CoreDsl.constantUsersPerSec;
import static io.gatling.javaapi.core.CoreDsl.global;
import static io.gatling.javaapi.core.CoreDsl.rampUsersPerSec;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

/**
 * 膝点负载探测 Simulation。
 * 从低负载开始逐段爬升到峰值，随后短平台与平滑回落，降低结束瞬时抖动噪声。
 * 膝点 RPS 写入 baseline.json 的 kneeRps 字段。
 * 覆盖需求 22.1, 22.3, 20.4。
 *
 * <p>注意：Gatling 不支持运行时动态停止注入，此处预设最大阶段数与峰值后回落阶段。
 * 实际膝点由 after() 中读取系统属性（CI 解析报告后回写）或使用默认估算值确定。
 */
public class KneeDetectionSimulation extends Simulation {

    private static final int START_USERS = 100;
    private static final int STEP_USERS = 980;
    private static final int STEP_DURATION_SECONDS = 15;
    private static final int MAX_STEPS = 5;
    private static final int PEAK_HOLD_SECONDS = 5;
    private static final int COOL_DOWN_SECONDS = 5;
    private static final int COOL_DOWN_TARGET_USERS = 100;

    private final String baseUrl = System.getProperty("baseUrl", "http://localhost:8080");
    private final int clientMaxConnectionsPerHost =
            Integer.parseInt(System.getProperty("clientMaxConnectionsPerHost", "300"));
    private final int requestTimeoutMillis =
            Integer.parseInt(System.getProperty("requestTimeoutMillis", "1200"));
    private final int steadyWindowTrimSeconds =
            Integer.parseInt(System.getProperty("steadyWindowTrimSeconds", "5"));

    private final HttpProtocolBuilder httpProtocol = http.baseUrl(baseUrl)
            .acceptHeader("application/json")
            .disableWarmUp()
            .shareConnections()
            .maxConnectionsPerHost(clientMaxConnectionsPerHost);

    private final ScenarioBuilder kneeScenario = scenario("膝点探测-GET-hello")
            .exec(http("GET /api/example/hello")
                    .get("/api/example/hello")
                    .requestTimeout(Duration.ofMillis(requestTimeoutMillis))
                    .check(status().in(200, 429, 503)));

    private final EnvSnapshotData envStart = EnvSnapshot.capture();

    {
        // 构建注入曲线：分段上坡 -> 峰值短平台 -> 平滑回落。
        List<OpenInjectionStep> steps = new ArrayList<>();
        for (int step = 0; step < MAX_STEPS; step++) {
            int fromUsers = START_USERS + step * STEP_USERS;
            int toUsers = START_USERS + (step + 1) * STEP_USERS;
            steps.add(rampUsersPerSec(fromUsers).to(toUsers)
                    .during(Duration.ofSeconds(STEP_DURATION_SECONDS)));
        }
        int peakUsers = START_USERS + MAX_STEPS * STEP_USERS;
        steps.add(constantUsersPerSec(peakUsers)
                .during(Duration.ofSeconds(PEAK_HOLD_SECONDS)));
        steps.add(rampUsersPerSec(peakUsers).to(COOL_DOWN_TARGET_USERS)
                .during(Duration.ofSeconds(COOL_DOWN_SECONDS)));

        setUp(kneeScenario.injectOpen(steps.toArray(new OpenInjectionStep[0])))
                .protocols(httpProtocol)
                .assertions(
                        // 整体错误率 < 10%（膝点探测允许部分阶段超出）
                        global().failedRequests().percent().lt(10.0)
                );
    }

    @Override
    public void after() {
        EnvSnapshot.captureEnd(envStart);
        saveKneeRps();
        printSteadyWindowSummary();
    }

    private void saveKneeRps() {
        // 从系统属性读取 CI 解析报告后回写的膝点 RPS
        // 若未设置，则使用保守估算：MAX_STEPS 阶段中间点对应的 RPS
        long kneeRps = Long.parseLong(System.getProperty("gatling.result.kneeRps",
                String.valueOf((long) (START_USERS + MAX_STEPS / 2 * STEP_USERS))));

        System.out.println("膝点 RPS：" + kneeRps);

        BaselineManager bm = new BaselineManager();
        Optional<PerfMetrics> existing = bm.load();

        EnvSnapshotSummary envSummary = new EnvSnapshotSummary(
                envStart.cpuCores(), envStart.jvmVersion(), envStart.availableMemoryMb());

        if (existing.isPresent()) {
            PerfMetrics current = existing.get();
            PerfMetrics updated = new PerfMetrics(
                    current.rps(), current.p95Ms(), current.p99Ms(), current.errorRate(),
                    current.mockRps(), kneeRps, envSummary, Instant.now()
            );
            bm.save(updated);
        } else {
            PerfMetrics initial = new PerfMetrics(0, 0, 0, 0, 0, kneeRps, envSummary, Instant.now());
            bm.save(initial);
            System.out.println("初始基线已创建（kneeRps=" + kneeRps + "）");
        }
    }

    private void printSteadyWindowSummary() {
        Optional<Path> logFile = findLatestSimulationLog();
        if (logFile.isEmpty()) {
            System.out.println("稳态窗口统计：未找到 simulation.log，跳过");
            return;
        }
        Optional<SteadyWindowStats> stats = parseSteadyWindowStats(logFile.get(),
                steadyWindowTrimSeconds);
        if (stats.isEmpty()) {
            System.out.println("稳态窗口统计：日志内容不足，跳过");
            return;
        }
        SteadyWindowStats value = stats.get();
        String windowDesc = value.windowEndSecond() >= 0
                ? String.format("0~%ds", value.windowEndSecond())
                : "空窗口";
        System.out.printf(
                "稳态窗口统计（剔除末尾%d秒，窗口=%s）：total=%d, ko=%d, koRate=%.4f%%%n",
                steadyWindowTrimSeconds, windowDesc, value.total(),
                value.ko(), value.koRatePercent());
    }

    private Optional<Path> findLatestSimulationLog() {
        Path gatlingDir = Path.of("target", "gatling");
        if (!Files.isDirectory(gatlingDir)) {
            return Optional.empty();
        }
        String reportPrefix = "kneedetectionsimulation-";
        try (Stream<Path> paths = Files.list(gatlingDir)) {
            return paths.filter(Files::isDirectory)
                    .filter(path -> path.getFileName().toString().startsWith(reportPrefix))
                    .map(path -> path.resolve("simulation.log"))
                    .filter(Files::isRegularFile)
                    .max(Comparator.comparingLong(this::lastModifiedMillis));
        } catch (IOException ex) {
            return Optional.empty();
        }
    }

    private Optional<SteadyWindowStats> parseSteadyWindowStats(Path logPath, int trimTailSeconds) {
        long runStart = -1L;
        Map<Integer, Long> totalPerSecond = new HashMap<>();
        Map<Integer, Long> koPerSecond = new HashMap<>();

        try (BufferedReader reader = Files.newBufferedReader(logPath, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("RUN\t")) {
                    String[] fields = line.split("\t", -1);
                    if (fields.length > 3) {
                        runStart = safeParseLong(fields[3], runStart);
                    }
                    continue;
                }
                if (runStart < 0 || !line.startsWith("REQUEST\t")) {
                    continue;
                }

                String[] fields = line.split("\t", -1);
                if (fields.length < 6) {
                    continue;
                }
                long endTimeMillis = safeParseLong(fields[4], -1L);
                if (endTimeMillis < 0) {
                    continue;
                }
                int second = (int) ((endTimeMillis - runStart) / 1000);
                totalPerSecond.merge(second, 1L, Long::sum);
                if ("KO".equals(fields[5])) {
                    koPerSecond.merge(second, 1L, Long::sum);
                }
            }
        } catch (IOException ex) {
            return Optional.empty();
        }

        if (runStart < 0 || totalPerSecond.isEmpty()) {
            return Optional.empty();
        }

        int lastSecond = totalPerSecond.keySet().stream()
                .max(Integer::compareTo)
                .orElse(0);
        int windowEndSecond = lastSecond - Math.max(0, trimTailSeconds);
        long total = 0L;
        long ko = 0L;
        for (Map.Entry<Integer, Long> entry : totalPerSecond.entrySet()) {
            if (entry.getKey() <= windowEndSecond) {
                total += entry.getValue();
            }
        }
        for (Map.Entry<Integer, Long> entry : koPerSecond.entrySet()) {
            if (entry.getKey() <= windowEndSecond) {
                ko += entry.getValue();
            }
        }
        double koRatePercent = total > 0 ? (ko * 100.0) / total : 0.0;
        return Optional.of(new SteadyWindowStats(windowEndSecond, total, ko, koRatePercent));
    }

    private long lastModifiedMillis(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException ex) {
            return Long.MIN_VALUE;
        }
    }

    private long safeParseLong(String value, long defaultValue) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }

    private record SteadyWindowStats(
            int windowEndSecond,
            long total,
            long ko,
            double koRatePercent) {
    }
}
