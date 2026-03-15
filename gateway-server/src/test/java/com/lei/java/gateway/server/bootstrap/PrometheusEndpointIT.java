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
package com.lei.java.gateway.server.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.lei.java.gateway.server.config.GatewayServerConfig;
import com.lei.java.gateway.server.http.DefaultErrorResponseMapper;
import com.lei.java.gateway.server.http.DefaultHeaderPolicyService;
import com.lei.java.gateway.server.metrics.PrometheusGatewayMetricsService;
import com.lei.java.gateway.server.proxy.DefaultTimeoutPolicy;
import com.lei.java.gateway.server.routing.StaticRouteService;

import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;

class PrometheusEndpointIT {

    @Test
    void shouldExposePrometheusMetricsWhenRequestMetricsPath() throws Exception {
        final PrometheusMeterRegistry meterRegistry =
                new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        final GatewayBootstrap bootstrap =
                new GatewayBootstrap(
                        new StaticRouteService(),
                        new DefaultHeaderPolicyService(),
                        new DefaultTimeoutPolicy(),
                        new DefaultErrorResponseMapper(),
                        new PrometheusGatewayMetricsService(meterRegistry));
        bootstrap.start(new GatewayServerConfig(0, 1024 * 1024, true, List.of()));

        try {
            final HttpClient client = HttpClient.newHttpClient();
            final HttpResponse<String> healthResponse =
                    client.send(
                            HttpRequest.newBuilder(
                                            URI.create(
                                                    "http://127.0.0.1:"
                                                            + bootstrap.boundPort()
                                                            + "/health"))
                                    .GET()
                                    .build(),
                            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            assertEquals(200, healthResponse.statusCode());

            final HttpResponse<String> metricsResponse =
                    client.send(
                            HttpRequest.newBuilder(
                                            URI.create(
                                                    "http://127.0.0.1:"
                                                            + bootstrap.boundPort()
                                                            + "/metrics/prometheus"))
                                    .GET()
                                    .build(),
                            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            assertEquals(200, metricsResponse.statusCode());
            assertTrue(metricsResponse.body().contains("gateway_http_requests_total"));
            assertTrue(metricsResponse.body().contains("gateway_http_request_duration_seconds"));
            assertTrue(metricsResponse.body().contains("gateway_http_inflight_requests"));
            assertTrue(metricsResponse.body().contains("route_id=\"local_health\""));
        } finally {
            bootstrap.stop();
            meterRegistry.close();
        }
    }

    @Test
    void shouldReturn403WhenClientIpNotInManagementAllowList() throws Exception {
        final PrometheusMeterRegistry meterRegistry =
                new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        final GatewayBootstrap bootstrap =
                new GatewayBootstrap(
                        new StaticRouteService(),
                        new DefaultHeaderPolicyService(),
                        new DefaultTimeoutPolicy(),
                        new DefaultErrorResponseMapper(),
                        new PrometheusGatewayMetricsService(meterRegistry));
        bootstrap.start(
                new GatewayServerConfig(
                        0, 1024 * 1024, true, List.of(), true, true, List.of("10.0.0.1"), 1024));

        try {
            final HttpClient client = HttpClient.newHttpClient();
            final HttpResponse<String> healthResponse =
                    client.send(
                            HttpRequest.newBuilder(
                                            URI.create(
                                                    "http://127.0.0.1:"
                                                            + bootstrap.boundPort()
                                                            + "/health"))
                                    .GET()
                                    .build(),
                            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            assertEquals(403, healthResponse.statusCode());
            assertTrue(
                    healthResponse.body().contains("\"code\":\"MANAGEMENT_ENDPOINT_FORBIDDEN\""));

            final HttpResponse<String> metricsResponse =
                    client.send(
                            HttpRequest.newBuilder(
                                            URI.create(
                                                    "http://127.0.0.1:"
                                                            + bootstrap.boundPort()
                                                            + "/metrics/prometheus"))
                                    .GET()
                                    .build(),
                            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            assertEquals(403, metricsResponse.statusCode());
            assertTrue(
                    metricsResponse.body().contains("\"code\":\"MANAGEMENT_ENDPOINT_FORBIDDEN\""));
        } finally {
            bootstrap.stop();
            meterRegistry.close();
        }
    }
}
