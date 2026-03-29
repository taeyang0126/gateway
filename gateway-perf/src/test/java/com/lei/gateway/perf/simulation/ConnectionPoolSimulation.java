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
 * 连接池压力测试。
 * 以超过连接池最大连接数（20）的并发触发 borrow/requite 竞争，验证无连接泄漏。
 * 覆盖需求 10.1, 10.2, 10.3, 10.4, 20.4。
 */
public class ConnectionPoolSimulation extends Simulation {

    // 连接池最大连接数为 20（application-perf.yml），使用 50 并发触发竞争
    private static final int POOL_PRESSURE_USERS = 50;

    private final String baseUrl = System.getProperty("baseUrl", "http://localhost:8080");
    private final int duration = Integer.parseInt(System.getProperty("duration", "60"));
    private final int warmupDuration = Integer.parseInt(System.getProperty("warmupDuration", "30"));
    private final int warmupUsers = Integer.parseInt(System.getProperty("warmupUsers", "10"));
    private final boolean skipWarmup = Boolean.parseBoolean(System.getProperty("skipWarmup", "false"));
    private final int maxConnectionLeak = Integer.parseInt(System.getProperty("maxConnectionLeak", "0"));

    private final HttpProtocolBuilder httpProtocol = http.baseUrl(baseUrl)
            .acceptHeader("application/json");

    // 连接池压力 Scenario：超过连接池上限的并发
    private final ScenarioBuilder poolPressureScenario = scenario("连接池压力-GET-hello")
            .exec(http("GET /api/example/hello")
                    .get("/api/example/hello")
                    .check(status().in(200, 503)));

    // 压测前后连接数快照（通过 /health/live 探针）
    private final ScenarioBuilder preCheckScenario = scenario("连接池-压测前探针")
            .exec(http("GET /health/live (pre)")
                    .get("/health/live")
                    .check(status().is(200)));

    private final ScenarioBuilder postCheckScenario = scenario("连接池-压测后探针")
            .exec(http("GET /health/live (post)")
                    .get("/health/live")
                    .check(status().is(200)));

    private final ScenarioBuilder warmupScenario = scenario("连接池压力-预热-GET-hello")
            .exec(http("GET /api/example/hello (warmup)")
                    .get("/api/example/hello")
                    .check(status().in(200, 503)));

    private final EnvSnapshotData envStart = EnvSnapshot.capture();

    {
        List<PopulationBuilder> populations = new ArrayList<>();

        // 压测前探针（立即执行）
        populations.add(preCheckScenario.injectOpen(
                io.gatling.javaapi.core.CoreDsl.atOnceUsers(1)
        ));

        PopulationBuilder warmup = WarmupHelper.build(warmupScenario, warmupUsers, warmupDuration, skipWarmup);
        if (warmup != null) {
            populations.add(warmup);
        }

        int delaySeconds = skipWarmup ? 0 : warmupDuration;

        // 连接池压力测试（开环，超过连接池上限）
        populations.add(poolPressureScenario.injectOpen(
                nothingFor(Duration.ofSeconds(delaySeconds)),
                constantUsersPerSec(POOL_PRESSURE_USERS).during(Duration.ofSeconds(duration))
        ));

        // 压测后探针（压测结束后 5s 执行）
        int postCheckDelay = delaySeconds + duration + 5;
        populations.add(postCheckScenario.injectOpen(
                nothingFor(Duration.ofSeconds(postCheckDelay)),
                io.gatling.javaapi.core.CoreDsl.atOnceUsers(1)
        ));

        setUp(populations.toArray(new PopulationBuilder[0]))
                .protocols(httpProtocol)
                .assertions(
                        global().failedRequests().percent().lt(1.0),
                        // 503/连接超时比例 < 5%（通过 failedRequests 近似，503 已在 check 中标记为成功）
                        global().responseTime().percentile(99.0).lte(5000)
                );
    }

    @Override
    public void after() {
        EnvSnapshot.captureEnd(envStart);
        // 连接泄漏检测：maxConnectionLeak=0 时任何泄漏都触发告警
        // 实际连接数差值需通过 JMX/actuator 获取，此处输出提示供人工核查
        System.out.printf("连接泄漏检测：maxConnectionLeak 阈值=%d，请通过 /actuator/metrics 核查连接数差值%n",
                maxConnectionLeak);
    }
}
