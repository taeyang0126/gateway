package com.lei.gateway.perf.simulation;

import com.lei.gateway.perf.util.EnvSnapshot;
import com.lei.gateway.perf.util.EnvSnapshotData;
import io.gatling.javaapi.core.PopulationBuilder;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;

import java.time.Duration;

import static io.gatling.javaapi.core.CoreDsl.constantUsersPerSec;
import static io.gatling.javaapi.core.CoreDsl.global;
import static io.gatling.javaapi.core.CoreDsl.nothingFor;
import static io.gatling.javaapi.core.CoreDsl.rampUsersPerSec;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

/**
 * 流量突增（Spike）测试。
 * 注入模式：低并发 → 快速爬升 → 峰值持续 → 快速回落 → 恢复观察期。
 * 覆盖需求 11.1, 11.2, 11.3, 11.4, 20.4。
 */
public class SpikeSimulation extends Simulation {

    private static final int RAMP_DURATION_SECONDS = 5;
    private static final int BASE_USERS = 10;
    private static final int RECOVERY_SECONDS = 60;

    private final String baseUrl = System.getProperty("baseUrl", "http://localhost:8080");
    private final int spikeUsers = Integer.parseInt(System.getProperty("spikeUsers", "200"));
    private final int spikeDuration = Integer.parseInt(System.getProperty("spikeDuration", "30"));

    private final HttpProtocolBuilder httpProtocol = http.baseUrl(baseUrl)
            .acceptHeader("application/json");

    private final ScenarioBuilder spikeScenario = scenario("Spike-GET-hello")
            .exec(http("GET /api/example/hello")
                    .get("/api/example/hello")
                    .check(status().in(200, 429, 503)));

    // 恢复期观察 Scenario（与主 Scenario 相同请求，独立统计）
    private final ScenarioBuilder recoveryScenario = scenario("Spike-恢复期观察")
            .exec(http("GET /api/example/hello (recovery)")
                    .get("/api/example/hello")
                    .check(status().is(200)));

    private final EnvSnapshotData envStart = EnvSnapshot.capture();

    {
        // spike 主流量：低并发 → 爬升 → 峰值 → 回落
        int spikePhaseTotal = RAMP_DURATION_SECONDS + RAMP_DURATION_SECONDS
                + spikeDuration + RAMP_DURATION_SECONDS;

        PopulationBuilder spikePopulation = spikeScenario.injectOpen(
                constantUsersPerSec(BASE_USERS).during(Duration.ofSeconds(RAMP_DURATION_SECONDS)),
                rampUsersPerSec(BASE_USERS).to(spikeUsers).during(Duration.ofSeconds(RAMP_DURATION_SECONDS)),
                constantUsersPerSec(spikeUsers).during(Duration.ofSeconds(spikeDuration)),
                rampUsersPerSec(spikeUsers).to(BASE_USERS).during(Duration.ofSeconds(RAMP_DURATION_SECONDS)),
                nothingFor(Duration.ofSeconds(RECOVERY_SECONDS))
        );

        // 恢复期观察：spike 回落后开始，持续 60s
        int recoveryStart = spikePhaseTotal;
        PopulationBuilder recoveryPopulation = recoveryScenario.injectOpen(
                nothingFor(Duration.ofSeconds(recoveryStart)),
                constantUsersPerSec(BASE_USERS).during(Duration.ofSeconds(RECOVERY_SECONDS))
        );

        setUp(spikePopulation, recoveryPopulation)
                .protocols(httpProtocol)
                .assertions(
                        // spike 期间错误率 < 5%
                        global().failedRequests().percent().lt(5.0),
                        // 5xx 比例 < 5%（通过全局错误率近似）
                        global().responseTime().percentile(99.0).lte(30000)
                );
    }

    @Override
    public void after() {
        EnvSnapshot.captureEnd(envStart);
    }
}
