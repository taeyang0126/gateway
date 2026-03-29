package com.lei.gateway.perf.simulation;

import com.lei.gateway.perf.util.EnvSnapshot;
import com.lei.gateway.perf.util.EnvSnapshotData;
import com.lei.gateway.perf.util.WarmupHelper;
import io.gatling.javaapi.core.PopulationBuilder;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static io.gatling.javaapi.core.CoreDsl.atOnceUsers;
import static io.gatling.javaapi.core.CoreDsl.constantUsersPerSec;
import static io.gatling.javaapi.core.CoreDsl.global;
import static io.gatling.javaapi.core.CoreDsl.nothingFor;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

/**
 * 限流场景性能测试。
 * 覆盖需求 3（限流）、需求 20（预热）、需求 24（开环）。
 */
public class RateLimitSimulation extends Simulation {

    private final String baseUrl = System.getProperty("baseUrl", "http://localhost:8080");
    private final double rateLimitRps = Double.parseDouble(System.getProperty("rateLimitRps", "100"));
    private final int duration = Integer.parseInt(System.getProperty("duration", "60"));
    private final int warmupDuration = Integer.parseInt(System.getProperty("warmupDuration", "30"));
    private final int warmupUsers = Integer.parseInt(System.getProperty("warmupUsers", "10"));
    private final boolean skipWarmup = Boolean.parseBoolean(System.getProperty("skipWarmup", "false"));

    // 以超过限流阈值 2 倍的速率注入，确保触发 429
    private final double injectRps = rateLimitRps * 2;

    private final HttpProtocolBuilder httpProtocol = http.baseUrl(baseUrl)
            .acceptHeader("application/json");

    // 限流压测 Scenario：将 429 视为预期响应
    private final ScenarioBuilder rateLimitScenario = scenario("限流压测-GET-perf-hello")
            .exec(http("GET /api/perf/hello")
                    .get("/api/perf/hello")
                    .check(status().in(200, 429)));

    // 存活探针 Scenario：限流场景结束后验证网关存活
    private final ScenarioBuilder healthCheckScenario = scenario("存活探针-GET-health-live")
            .exec(http("GET /health/live")
                    .get("/health/live")
                    .check(status().is(200)));

    private final ScenarioBuilder warmupScenario = scenario("限流压测-预热-GET-perf-hello")
            .exec(http("GET /api/perf/hello (warmup)")
                    .get("/api/perf/hello")
                    .check(status().in(200, 429)));

    private final EnvSnapshotData envStart = EnvSnapshot.capture();

    {
        List<PopulationBuilder> populations = new ArrayList<>();

        PopulationBuilder warmup = WarmupHelper.build(warmupScenario, warmupUsers, warmupDuration, skipWarmup);
        if (warmup != null) {
            populations.add(warmup);
        }

        int delaySeconds = skipWarmup ? 0 : warmupDuration;

        // 限流压测（开环，超过限流阈值）
        populations.add(rateLimitScenario.injectOpen(
                nothingFor(Duration.ofSeconds(delaySeconds)),
                constantUsersPerSec(injectRps).during(Duration.ofSeconds(duration))
        ));

        // 存活探针：在限流场景结束后（warmup + duration + 5s 缓冲）发送单次请求
        int healthCheckDelay = delaySeconds + duration + 5;
        populations.add(healthCheckScenario.injectOpen(
                nothingFor(Duration.ofSeconds(healthCheckDelay)),
                atOnceUsers(1)
        ));

        setUp(populations.toArray(new PopulationBuilder[0]))
                .protocols(httpProtocol)
                .assertions(
                        // 2xx + 429 合计比例 > 99%（即非预期错误率 < 1%）
                        global().failedRequests().percent().lt(1.0)
                );

    }

    @Override
    public void after() {
        EnvSnapshot.captureEnd(envStart);
    }
}
