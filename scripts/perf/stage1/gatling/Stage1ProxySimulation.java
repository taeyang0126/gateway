/*
 * Copyright (c) 2026 lei.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package stage1;

import static io.gatling.javaapi.core.CoreDsl.StringBody;
import static io.gatling.javaapi.core.CoreDsl.constantConcurrentUsers;
import static io.gatling.javaapi.core.CoreDsl.exec;
import static io.gatling.javaapi.core.CoreDsl.forever;
import static io.gatling.javaapi.core.CoreDsl.holdFor;
import static io.gatling.javaapi.core.CoreDsl.reachRps;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

import java.time.Duration;

import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;

/** Stage1 网关压测场景。 */
public final class Stage1ProxySimulation extends Simulation {

    private static final String DEFAULT_BASE_URL = "http://127.0.0.1:8080";

    /** 构造并注册压测场景。 */
    public Stage1ProxySimulation() {
        final String baseUrl = env("BASE_URL", DEFAULT_BASE_URL);
        final int durationMinutes = Integer.parseInt(env("DURATION_MINUTES", "10"));
        final double requestsPerSecond = Double.parseDouble(env("RPS", "2000"));
        final int bodyBytes = Integer.parseInt(env("BODY_BYTES", "1024"));
        final String scenarioPath = env("SCENARIO_PATH", "/api/ping");
        final String scenarioMethod = env("SCENARIO_METHOD", "GET").toUpperCase();

        final HttpProtocolBuilder httpProtocol =
                http.baseUrl(baseUrl)
                        .acceptHeader("application/json, text/plain, */*")
                        .shareConnections()
                        .userAgentHeader("stage1-gatling");

        final String payload = "x".repeat(Math.max(0, bodyBytes));
        final ChainBuilder requestChain = buildRequest(scenarioMethod, scenarioPath, payload);
        final ScenarioBuilder scenarioBuilder = scenario("stage1-proxy").exec(forever().on(requestChain));
        final int concurrentUsers = Math.max(64, (int) Math.ceil(requestsPerSecond / 16.0));
        final Duration maxDuration = Duration.ofMinutes(durationMinutes).plusSeconds(20);
        final Duration loadDuration = Duration.ofMinutes(durationMinutes).plusSeconds(15);

        setUp(
                        scenarioBuilder.injectClosed(
                                constantConcurrentUsers(concurrentUsers).during(loadDuration)))
                .throttle(
                        reachRps((int) Math.round(requestsPerSecond)).in(Duration.ofSeconds(15)),
                        holdFor(Duration.ofMinutes(durationMinutes)))
                .maxDuration(maxDuration)
                .protocols(httpProtocol);
    }

    private static ChainBuilder buildRequest(
            final String method, final String path, final String payload) {
        if ("POST".equals(method)) {
            return exec(
                    http("stage1_proxy_request")
                            .post(path)
                            .body(StringBody(payload))
                            .header("Content-Type", "text/plain")
                            .check(status().in(200, 201, 202, 204)));
        }
        return exec(
                http("stage1_proxy_request")
                        .get(path)
                        .check(status().in(200, 201, 202, 204)));
    }

    private static String env(final String key, final String defaultValue) {
        final String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return value;
    }
}
