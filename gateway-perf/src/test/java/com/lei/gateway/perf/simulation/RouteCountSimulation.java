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
 * 多路由规则性能对比测试。
 * 分别在 1 条、10 条、50 条路由配置下测量 RPS，验证路径匹配开销在 50 条规则内可接受。
 * 覆盖需求 14.2, 14.3, 14.4, 20.4。
 */
public class RouteCountSimulation extends Simulation {

    private final String baseUrl = System.getProperty("baseUrl", "http://localhost:8080");
    private final double users = Double.parseDouble(System.getProperty("users", "50"));
    private final int duration = Integer.parseInt(System.getProperty("duration", "60"));
    private final int warmupDuration = Integer.parseInt(System.getProperty("warmupDuration", "30"));
    private final int warmupUsers = Integer.parseInt(System.getProperty("warmupUsers", "10"));
    private final boolean skipWarmup = Boolean.parseBoolean(System.getProperty("skipWarmup", "false"));
    // 路由数量变体，默认 "1,10,50"
    private final String routeCountVariants = System.getProperty("routeCountVariants", "1,10,50");

    private final HttpProtocolBuilder httpProtocol = http.baseUrl(baseUrl)
            .acceptHeader("application/json");

    private final EnvSnapshotData envStart = EnvSnapshot.capture();

    {
        List<PopulationBuilder> populations = new ArrayList<>();

        // 预热使用 route1
        ScenarioBuilder warmupScenario = scenario("路由对比-预热")
                .exec(http("GET /api/perf/route1/hello (warmup)")
                        .get("/api/perf/route1/hello")
                        .check(status().is(200)));

        PopulationBuilder warmup = WarmupHelper.build(warmupScenario, warmupUsers, warmupDuration, skipWarmup);
        if (warmup != null) {
            populations.add(warmup);
        }

        int delaySeconds = skipWarmup ? 0 : warmupDuration;

        // 解析路由数量变体，为每个变体创建独立 Scenario
        String[] variants = routeCountVariants.split(",");
        for (String variant : variants) {
            int routeCount = Integer.parseInt(variant.trim());
            String routePath = resolveRoutePath(routeCount);
            String scenarioName = "路由对比-" + routeCount + "条路由";

            ScenarioBuilder routeScenario = scenario(scenarioName)
                    .exec(http("GET " + routePath + " (" + routeCount + " routes)")
                            .get(routePath)
                            .check(status().is(200)));

            populations.add(routeScenario.injectOpen(
                    nothingFor(Duration.ofSeconds(delaySeconds)),
                    constantUsersPerSec(users).during(Duration.ofSeconds(duration))
            ));
        }

        setUp(populations.toArray(new PopulationBuilder[0]))
                .protocols(httpProtocol)
                .assertions(
                        global().failedRequests().percent().lt(1.0)
                        // 50 条路由 RPS 退化不超过 1 条路由 RPS 的 10%
                        // 由于 Gatling Java API 不支持跨 Scenario 的 Assertion，
                        // 此项退化对比需通过报告人工核查或 CI 脚本解析报告实现
                );
    }

    /**
     * 根据路由数量返回对应的测试路径。
     * 1 条路由 → /api/perf/route1/hello；
     * 10 条路由 → /api/perf/route10/hello；
     * 50 条路由 → /api/perf/route50/hello。
     */
    private String resolveRoutePath(int routeCount) {
        if (routeCount <= 1) {
            return "/api/perf/route1/hello";
        }
        // 使用该变体中编号最大的路由，确保经过最多路由规则的匹配扫描
        return "/api/perf/route" + routeCount + "/hello";
    }

    @Override
    public void after() {
        EnvSnapshot.captureEnd(envStart);
    }
}
