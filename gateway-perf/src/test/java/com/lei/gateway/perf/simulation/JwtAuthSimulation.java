package com.lei.gateway.perf.simulation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lei.gateway.perf.util.EnvSnapshot;
import com.lei.gateway.perf.util.EnvSnapshotData;
import com.lei.gateway.perf.util.WarmupHelper;
import io.gatling.javaapi.core.PopulationBuilder;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
 * JWT 认证路径性能测试。
 * 覆盖需求 5（JWT 认证）、需求 20（预热）、需求 24（开环）。
 */
public class JwtAuthSimulation extends Simulation {

    private final String baseUrl = System.getProperty("baseUrl", "http://localhost:8080");
    private final String authUrl = System.getProperty("authUrl", "http://localhost:8091");
    private final int users = Integer.parseInt(System.getProperty("users", "50"));
    private final int duration = Integer.parseInt(System.getProperty("duration", "60"));
    private final double targetRps = Double.parseDouble(System.getProperty("targetRps", "5000"));
    private final int warmupDuration = Integer.parseInt(System.getProperty("warmupDuration", "30"));
    private final int warmupUsers = Integer.parseInt(System.getProperty("warmupUsers", "10"));
    private final boolean skipWarmup = Boolean.parseBoolean(System.getProperty("skipWarmup", "false"));

    // ---- 启动阶段同步获取 JWT Token ----
    private final String jwtToken = fetchJwtToken();

    private String fetchJwtToken() {
        try {
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(authUrl + "/api/auth-jwt/token"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"userId\":\"perf-test-user\"}"))
                    .timeout(Duration.ofSeconds(10))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new RuntimeException("JWT Token 获取失败，无法执行认证场景，HTTP 状态码: "
                        + response.statusCode());
            }

            ObjectMapper mapper = new ObjectMapper();
            JsonNode node = mapper.readTree(response.body());
            JsonNode tokenNode = node.get("accessToken");
            if (tokenNode == null || tokenNode.isNull()) {
                throw new RuntimeException("JWT Token 获取失败，无法执行认证场景，响应体缺少 accessToken 字段");
            }
            return tokenNode.asText();
        } catch (IOException | InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("JWT Token 获取失败，无法执行认证场景", ex);
        }
    }

    private final HttpProtocolBuilder httpProtocol = http.baseUrl(baseUrl)
            .acceptHeader("application/json")
            .contentTypeHeader("application/json");

    // Scenario A：JWT 认证路径
    private final ScenarioBuilder scenarioA = scenario("JWT认证-GET-private-profile")
            .exec(http("GET /api/example/private/profile")
                    .get("/api/example/private/profile")
                    .header("Authorization", "Bearer " + jwtToken)
                    .check(status().is(200)));

    // Scenario B：非认证对比路径
    private final ScenarioBuilder scenarioB = scenario("非认证对比-GET-hello")
            .exec(http("GET /api/example/hello")
                    .get("/api/example/hello")
                    .check(status().is(200)));

    private final ScenarioBuilder warmupScenario = scenario("JWT认证-预热-GET-private-profile")
            .exec(http("GET /api/example/private/profile (warmup)")
                    .get("/api/example/private/profile")
                    .header("Authorization", "Bearer " + jwtToken)
                    .check(status().is(200)));

    private final EnvSnapshotData envStart = EnvSnapshot.capture();

    {
        List<PopulationBuilder> populations = new ArrayList<>();

        PopulationBuilder warmup = WarmupHelper.build(warmupScenario, warmupUsers, warmupDuration, skipWarmup);
        if (warmup != null) {
            populations.add(warmup);
        }

        int delaySeconds = skipWarmup ? 0 : warmupDuration;

        populations.add(scenarioA.injectOpen(
                nothingFor(Duration.ofSeconds(delaySeconds)),
                constantUsersPerSec(users).during(Duration.ofSeconds(duration))
        ));
        populations.add(scenarioB.injectOpen(
                nothingFor(Duration.ofSeconds(delaySeconds)),
                constantUsersPerSec(users).during(Duration.ofSeconds(duration))
        ));

        setUp(populations.toArray(new PopulationBuilder[0]))
                .protocols(httpProtocol)
                .assertions(
                        global().failedRequests().percent().lt(1.0),
                        global().requestsPerSec().gte(targetRps)
                );

    }

    @Override
    public void after() {
        EnvSnapshot.captureEnd(envStart);
    }
}
