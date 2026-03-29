package com.lei.gateway.perf.simulation;

import com.lei.gateway.perf.util.EnvSnapshot;
import com.lei.gateway.perf.util.EnvSnapshotData;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;

import java.time.Duration;

import static io.gatling.javaapi.core.CoreDsl.constantUsersPerSec;
import static io.gatling.javaapi.core.CoreDsl.global;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

/**
 * 长时间稳定性测试（Soak Test）。
 * 持续低并发压测，验证网关长期运行的稳定性。
 * 堆内存采样由 profile.sh 负责，Simulation 只负责触发压测流量。
 * 覆盖需求 12.1, 12.2, 12.4, 12.5, 20.4。
 */
public class SoakSimulation extends Simulation {

    private final String baseUrl = System.getProperty("baseUrl", "http://localhost:8080");
    private final int soakDuration = Integer.parseInt(System.getProperty("soakDuration", "1800"));
    private final int soakUsers = Integer.parseInt(System.getProperty("soakUsers", "50"));

    private final HttpProtocolBuilder httpProtocol = http.baseUrl(baseUrl)
            .acceptHeader("application/json");

    private final ScenarioBuilder soakScenario = scenario("Soak-GET-hello")
            .exec(http("GET /api/example/hello")
                    .get("/api/example/hello")
                    .check(status().is(200)));

    private final EnvSnapshotData envStart = EnvSnapshot.capture();

    {
        setUp(soakScenario.injectOpen(
                constantUsersPerSec(soakUsers).during(Duration.ofSeconds(soakDuration))
        ))
                .protocols(httpProtocol)
                .assertions(
                        global().failedRequests().percent().lt(1.0)
                );
    }

    @Override
    public void after() {
        EnvSnapshot.captureEnd(envStart);
        System.out.println("Soak 测试完成。堆内存趋势请通过 profile.sh 生成的 resource-usage.csv 查看。");
    }
}
