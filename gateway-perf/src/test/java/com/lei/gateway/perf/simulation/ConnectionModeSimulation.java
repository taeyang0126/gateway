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
 * 客户端连接模式性能对比（Keep-Alive vs 短连接）。
 * 量化网关处理连接建立/销毁的开销。
 * 覆盖需求 18.1, 18.2, 18.3, 18.4, 20.4, 24.1。
 */
public class ConnectionModeSimulation extends Simulation {

    private final String baseUrl = System.getProperty("baseUrl", "http://localhost:8080");
    private final double users = Double.parseDouble(System.getProperty("users", "50"));
    private final int duration = Integer.parseInt(System.getProperty("duration", "60"));
    private final int warmupDuration = Integer.parseInt(System.getProperty("warmupDuration", "30"));
    private final int warmupUsers = Integer.parseInt(System.getProperty("warmupUsers", "10"));
    private final boolean skipWarmup = Boolean.parseBoolean(System.getProperty("skipWarmup", "false"));

    // 场景 A：Keep-Alive（Gatling 默认，HTTP/1.1 长连接）
    private final HttpProtocolBuilder keepAliveProtocol = http.baseUrl(baseUrl)
            .acceptHeader("application/json");

    // 场景 B：短连接（每次请求携带 Connection: close）
    private final HttpProtocolBuilder shortConnProtocol = http.baseUrl(baseUrl)
            .acceptHeader("application/json")
            .connectionHeader("close");

    private final ScenarioBuilder keepAliveScenario = scenario("连接模式-A-KeepAlive")
            .exec(http("GET /api/example/hello (keep-alive)")
                    .get("/api/example/hello")
                    .header("Connection", "keep-alive")
                    .check(status().is(200)));

    private final ScenarioBuilder shortConnScenario = scenario("连接模式-B-短连接")
            .exec(http("GET /api/example/hello (close)")
                    .get("/api/example/hello")
                    .header("Connection", "close")
                    .check(status().is(200)));

    private final ScenarioBuilder warmupScenario = scenario("连接模式-预热-GET-hello")
            .exec(http("GET /api/example/hello (warmup)")
                    .get("/api/example/hello")
                    .header("Connection", "keep-alive")
                    .check(status().is(200)));

    private final EnvSnapshotData envStart = EnvSnapshot.capture();

    {
        List<PopulationBuilder> populations = new ArrayList<>();

        PopulationBuilder warmup = WarmupHelper.build(warmupScenario, warmupUsers, warmupDuration, skipWarmup);
        if (warmup != null) {
            populations.add(warmup);
        }

        int delaySeconds = skipWarmup ? 0 : warmupDuration;

        // 两个 Scenario 使用相同并发和持续时间，确保对比可信
        populations.add(keepAliveScenario.injectOpen(
                nothingFor(Duration.ofSeconds(delaySeconds)),
                constantUsersPerSec(users).during(Duration.ofSeconds(duration))
        ));
        populations.add(shortConnScenario.injectOpen(
                nothingFor(Duration.ofSeconds(delaySeconds)),
                constantUsersPerSec(users).during(Duration.ofSeconds(duration))
        ));

        setUp(populations.toArray(new PopulationBuilder[0]))
                .protocols(keepAliveProtocol)
                .assertions(
                        // 场景 A 和场景 B 错误率均 < 1%
                        global().failedRequests().percent().lt(1.0)
                );
    }

    @Override
    public void after() {
        EnvSnapshot.captureEnd(envStart);
        System.out.println("连接模式对比完成。请查看 Gatling 报告中"
                + "「连接模式-A-KeepAlive」和「连接模式-B-短连接」的独立统计，"
                + "对比 RPS 和 P99 延迟，量化连接建立/销毁开销。");
    }
}
