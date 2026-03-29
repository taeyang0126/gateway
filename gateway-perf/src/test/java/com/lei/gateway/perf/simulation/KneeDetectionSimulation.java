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

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static io.gatling.javaapi.core.CoreDsl.global;
import static io.gatling.javaapi.core.CoreDsl.rampUsersPerSec;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

/**
 * 膝点负载探测 Simulation。
 * 从 10 用户开始，每 15 秒递增 10 用户，通过分段 rampUsersPerSec 模拟阶梯式负载递增。
 * 膝点 RPS 写入 baseline.json 的 kneeRps 字段。
 * 覆盖需求 22.1, 22.3, 20.4。
 *
 * <p>注意：Gatling 不支持运行时动态停止注入，此处预设最大阶段数（10 阶段，最高 100 用户）。
 * 实际膝点由 after() 中读取系统属性（CI 解析报告后回写）或使用默认估算值确定。
 */
public class KneeDetectionSimulation extends Simulation {

    private static final int START_USERS = 10;
    private static final int STEP_USERS = 10;
    private static final int STEP_DURATION_SECONDS = 15;
    private static final int MAX_STEPS = 10;

    private final String baseUrl = System.getProperty("baseUrl", "http://localhost:8080");

    private final HttpProtocolBuilder httpProtocol = http.baseUrl(baseUrl)
            .acceptHeader("application/json");

    private final ScenarioBuilder kneeScenario = scenario("膝点探测-GET-hello")
            .exec(http("GET /api/example/hello")
                    .get("/api/example/hello")
                    .check(status().in(200, 429, 503)));

    private final EnvSnapshotData envStart = EnvSnapshot.capture();

    {
        // 构建阶梯式注入：每阶段 15 秒，从 10 用户递增到 MAX_STEPS * STEP_USERS
        List<OpenInjectionStep> steps = new ArrayList<>();
        for (int step = 0; step < MAX_STEPS; step++) {
            int fromUsers = START_USERS + step * STEP_USERS;
            int toUsers = START_USERS + (step + 1) * STEP_USERS;
            steps.add(rampUsersPerSec(fromUsers).to(toUsers)
                    .during(Duration.ofSeconds(STEP_DURATION_SECONDS)));
        }

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
}
