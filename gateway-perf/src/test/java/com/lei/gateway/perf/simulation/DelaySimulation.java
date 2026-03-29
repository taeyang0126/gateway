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

import static io.gatling.javaapi.core.CoreDsl.constantUsersPerSec;
import static io.gatling.javaapi.core.CoreDsl.details;
import static io.gatling.javaapi.core.CoreDsl.global;
import static io.gatling.javaapi.core.CoreDsl.nothingFor;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

/**
 * 上游延迟场景性能测试。
 * 覆盖需求 9.6（延迟场景）、需求 9.7（Proxy Overhead 断言）、需求 20.4（预热）。
 */
public class DelaySimulation extends Simulation {

    private final String baseUrl = System.getProperty("baseUrl", "http://localhost:8080");
    private final double users = Double.parseDouble(System.getProperty("users", "50"));
    private final int duration = Integer.parseInt(System.getProperty("duration", "60"));
    private final int slowUsers = Integer.parseInt(System.getProperty("slowUsers", "10"));
    private final int warmupDuration = Integer.parseInt(System.getProperty("warmupDuration", "30"));
    private final int warmupUsers = Integer.parseInt(System.getProperty("warmupUsers", "10"));
    private final boolean skipWarmup = Boolean.parseBoolean(System.getProperty("skipWarmup", "false"));
    private final long maxProxyOverheadMs = Long.parseLong(System.getProperty("maxProxyOverheadMs", "20"));

    // 固定延迟 P99 上限：50ms（上游延迟）+ maxProxyOverheadMs（代理开销）
    private final long fixedDelayP99Limit = 50L + maxProxyOverheadMs;

    private final HttpProtocolBuilder httpProtocol = http.baseUrl(baseUrl)
            .acceptHeader("application/json");

    private final ScenarioBuilder fixedDelayScenario = scenario("延迟-固定50ms")
            .exec(http("GET /api/example/delay/fixed?ms=50")
                    .get("/api/example/delay/fixed?ms=50")
                    .check(status().is(200)));

    private final ScenarioBuilder randomDelayScenario = scenario("延迟-随机0-100ms")
            .exec(http("GET /api/example/delay/random?min=0&max=100")
                    .get("/api/example/delay/random?min=0&max=100")
                    .check(status().is(200)));

    private final ScenarioBuilder slowUpstreamScenario = scenario("延迟-慢上游500ms")
            .exec(http("GET /api/example/delay/slow")
                    .get("/api/example/delay/slow")
                    .check(status().is(200)));

    private final ScenarioBuilder warmupScenario = scenario("延迟-预热-固定50ms")
            .exec(http("GET /api/example/delay/fixed?ms=50 (warmup)")
                    .get("/api/example/delay/fixed?ms=50")
                    .check(status().is(200)));

    private final EnvSnapshotData envStart = EnvSnapshot.capture();

    {
        List<PopulationBuilder> populations = new ArrayList<>();

        PopulationBuilder warmup = WarmupHelper.build(warmupScenario, warmupUsers, warmupDuration, skipWarmup);
        if (warmup != null) {
            populations.add(warmup);
        }

        int delaySeconds = skipWarmup ? 0 : warmupDuration;

        populations.add(fixedDelayScenario.injectOpen(
                nothingFor(Duration.ofSeconds(delaySeconds)),
                constantUsersPerSec(users).during(Duration.ofSeconds(duration))
        ));

        populations.add(randomDelayScenario.injectOpen(
                nothingFor(Duration.ofSeconds(delaySeconds)),
                constantUsersPerSec(users).during(Duration.ofSeconds(duration))
        ));

        populations.add(slowUpstreamScenario.injectOpen(
                nothingFor(Duration.ofSeconds(delaySeconds)),
                constantUsersPerSec(slowUsers).during(Duration.ofSeconds(duration))
        ));

        setUp(populations.toArray(new PopulationBuilder[0]))
                .protocols(httpProtocol)
                .assertions(
                        global().failedRequests().percent().lt(1.0),
                        // 固定延迟 Scenario P99 ≤ 50ms + maxProxyOverheadMs（按请求名统计）
                        details("GET /api/example/delay/fixed?ms=50").responseTime().percentile(99.0).lte((int) fixedDelayP99Limit)
                );
    }

    @Override
    public void after() {
        EnvSnapshot.captureEnd(envStart);
    }
}
