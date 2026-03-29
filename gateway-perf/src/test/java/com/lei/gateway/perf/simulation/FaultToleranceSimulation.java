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
import static io.gatling.javaapi.core.CoreDsl.global;
import static io.gatling.javaapi.core.CoreDsl.nothingFor;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

/**
 * 上游不可用/超时容错测试。
 * 场景 A：慢上游超时（期望 504）；场景 B：上游宕机（期望 502）；场景 C：混合流量隔离验证。
 * 覆盖需求 13.1, 13.2, 13.3, 13.4, 20.4。
 */
public class FaultToleranceSimulation extends Simulation {

    private final String baseUrl = System.getProperty("baseUrl", "http://localhost:8080");
    private final double users = Double.parseDouble(System.getProperty("users", "50"));
    private final int duration = Integer.parseInt(System.getProperty("duration", "60"));
    private final int warmupDuration = Integer.parseInt(System.getProperty("warmupDuration", "30"));
    private final int warmupUsers = Integer.parseInt(System.getProperty("warmupUsers", "10"));
    private final boolean skipWarmup = Boolean.parseBoolean(System.getProperty("skipWarmup", "false"));

    private final HttpProtocolBuilder httpProtocol = http.baseUrl(baseUrl)
            .acceptHeader("application/json");

    // 场景 A：慢上游超时，期望网关返回 504
    private final ScenarioBuilder slowTimeoutScenario = scenario("容错-A-慢上游超时")
            .exec(http("GET /api/perf/slow (expect 504)")
                    .get("/api/perf/slow")
                    .check(status().is(504)));

    // 场景 B：上游宕机，期望网关返回 502
    private final ScenarioBuilder unreachableScenario = scenario("容错-B-上游宕机")
            .exec(http("GET /api/perf/unreachable (expect 502)")
                    .get("/api/perf/unreachable")
                    .check(status().is(502)));

    // 场景 C：混合流量 — 正常请求（50%）
    private final ScenarioBuilder normalScenario = scenario("容错-C-混合正常请求")
            .exec(http("GET /api/example/hello (mixed)")
                    .get("/api/example/hello")
                    .check(status().is(200)));

    // 场景 C：混合流量 — 慢上游请求（50%）
    private final ScenarioBuilder mixedSlowScenario = scenario("容错-C-混合慢上游请求")
            .exec(http("GET /api/example/delay/slow (mixed)")
                    .get("/api/example/delay/slow")
                    .check(status().in(200, 504)));

    private final ScenarioBuilder warmupScenario = scenario("容错-预热-GET-hello")
            .exec(http("GET /api/example/hello (warmup)")
                    .get("/api/example/hello")
                    .check(status().is(200)));

    private final EnvSnapshotData envStart = EnvSnapshot.capture();

    {
        List<PopulationBuilder> populations = new ArrayList<>();

        PopulationBuilder warmup = WarmupHelper.build(warmupScenario, warmupUsers, warmupDuration, skipWarmup);
        if (warmup != null) {
            populations.add(warmup);
        }

        int delaySeconds = skipWarmup ? 0 : warmupDuration;
        double halfUsers = users / 2.0;

        // 场景 A：慢上游超时
        populations.add(slowTimeoutScenario.injectOpen(
                nothingFor(Duration.ofSeconds(delaySeconds)),
                constantUsersPerSec(halfUsers).during(Duration.ofSeconds(duration))
        ));

        // 场景 B：上游宕机
        populations.add(unreachableScenario.injectOpen(
                nothingFor(Duration.ofSeconds(delaySeconds)),
                constantUsersPerSec(halfUsers).during(Duration.ofSeconds(duration))
        ));

        // 场景 C：混合流量（各 50%）
        populations.add(normalScenario.injectOpen(
                nothingFor(Duration.ofSeconds(delaySeconds)),
                constantUsersPerSec(halfUsers).during(Duration.ofSeconds(duration))
        ));
        populations.add(mixedSlowScenario.injectOpen(
                nothingFor(Duration.ofSeconds(delaySeconds)),
                constantUsersPerSec(halfUsers).during(Duration.ofSeconds(duration))
        ));

        setUp(populations.toArray(new PopulationBuilder[0]))
                .protocols(httpProtocol)
                .assertions(
                        // 场景 A/B 的 502/504 已在 check 中标记为成功，全局错误率应 < 5%
                        global().failedRequests().percent().lt(5.0)
                );
    }

    @Override
    public void after() {
        EnvSnapshot.captureEnd(envStart);
    }
}
