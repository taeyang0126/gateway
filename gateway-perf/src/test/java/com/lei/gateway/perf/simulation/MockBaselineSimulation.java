package com.lei.gateway.perf.simulation;

import com.lei.gateway.perf.util.BaselineManager;
import com.lei.gateway.perf.util.EnvSnapshot;
import com.lei.gateway.perf.util.EnvSnapshotData;
import com.lei.gateway.perf.util.EnvSnapshotSummary;
import com.lei.gateway.perf.util.PerfMetrics;
import com.lei.gateway.perf.util.WarmupHelper;
import io.gatling.javaapi.core.PopulationBuilder;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static io.gatling.javaapi.core.CoreDsl.constantUsersPerSec;
import static io.gatling.javaapi.core.CoreDsl.global;
import static io.gatling.javaapi.core.CoreDsl.nothingFor;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

/**
 * 网关自身基线测试（Mock 场景）。
 * 测量网关在上游零延迟下的理论最大 RPS，将 mockRps 写入 baseline.json。
 * 覆盖需求 17.2, 17.3, 17.4, 20.4。
 */
public class MockBaselineSimulation extends Simulation {

    private final String baseUrl = System.getProperty("baseUrl", "http://localhost:8080");
    private final double users = Double.parseDouble(System.getProperty("users", "50"));
    private final int duration = Integer.parseInt(System.getProperty("duration", "60"));
    private final int warmupDuration = Integer.parseInt(System.getProperty("warmupDuration", "30"));
    private final int warmupUsers = Integer.parseInt(System.getProperty("warmupUsers", "10"));
    private final boolean skipWarmup = Boolean.parseBoolean(System.getProperty("skipWarmup", "false"));

    private final HttpProtocolBuilder httpProtocol = http.baseUrl(baseUrl)
            .acceptHeader("application/json");

    private final ScenarioBuilder mockScenario = scenario("Mock基线-GET-mock")
            .exec(http("GET /api/example/mock")
                    .get("/api/example/mock")
                    .check(status().is(200)));

    private final ScenarioBuilder warmupScenario = scenario("Mock基线-预热-GET-mock")
            .exec(http("GET /api/example/mock (warmup)")
                    .get("/api/example/mock")
                    .check(status().is(200)));

    private final EnvSnapshotData envStart = EnvSnapshot.capture();

    {
        List<PopulationBuilder> populations = new ArrayList<>();

        PopulationBuilder warmup = WarmupHelper.build(warmupScenario, warmupUsers, warmupDuration, skipWarmup);
        if (warmup != null) {
            populations.add(warmup);
        }

        int delaySeconds = skipWarmup ? 0 : warmupDuration;

        populations.add(mockScenario.injectOpen(
                nothingFor(Duration.ofSeconds(delaySeconds)),
                constantUsersPerSec(users).during(Duration.ofSeconds(duration))
        ));

        setUp(populations.toArray(new PopulationBuilder[0]))
                .protocols(httpProtocol)
                .assertions(
                        global().failedRequests().percent().lt(1.0)
                );
    }

    @Override
    public void after() {
        EnvSnapshot.captureEnd(envStart);
        saveMockRps();
    }

    private void saveMockRps() {
        // 从系统属性读取本次 Mock RPS（由 CI 在报告解析后回写，或使用占位值）
        double mockRps = Double.parseDouble(System.getProperty("gatling.result.rps", "0"));

        BaselineManager bm = new BaselineManager();
        Optional<PerfMetrics> existing = bm.load();

        EnvSnapshotSummary envSummary = new EnvSnapshotSummary(
                envStart.cpuCores(), envStart.jvmVersion(), envStart.availableMemoryMb());

        if (existing.isPresent()) {
            PerfMetrics current = existing.get();
            // 校验 Mock RPS 应高于基础代理 RPS
            if (mockRps > 0 && current.rps() > 0 && mockRps <= current.rps()) {
                System.out.println("警告：Mock 场景 RPS 未高于基础代理场景，测试环境可能存在问题");
                throw new RuntimeException("Mock 场景 RPS（" + mockRps + "）未高于基础代理场景 RPS（"
                        + current.rps() + "），构建失败");
            }
            // 更新 mockRps 字段，保留其他字段
            PerfMetrics updated = new PerfMetrics(
                    current.rps(), current.p95Ms(), current.p99Ms(), current.errorRate(),
                    mockRps, current.kneeRps(), envSummary, Instant.now()
            );
            bm.save(updated);
        } else {
            // baseline.json 不存在，创建初始基线（mockRps 字段）
            PerfMetrics initial = new PerfMetrics(0, 0, 0, 0, mockRps, 0, envSummary, Instant.now());
            bm.save(initial);
            System.out.println("初始基线已创建（mockRps=" + mockRps + "）");
        }
    }
}
