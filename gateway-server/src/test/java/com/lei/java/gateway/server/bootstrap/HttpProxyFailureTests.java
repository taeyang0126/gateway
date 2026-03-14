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

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.lei.java.gateway.server.config.GatewayServerConfig;
import com.lei.java.gateway.server.config.HostRewriteMode;
import com.lei.java.gateway.server.config.MatchType;
import com.lei.java.gateway.server.config.RouteConfig;
import com.lei.java.gateway.server.config.UpstreamConfig;
import com.sun.net.httpserver.HttpServer;

class HttpProxyFailureTests {

    @Test
    void shouldReturn502WhenUpstreamUnavailable() throws Exception {
        final int unreachablePort = findUnusedPort();
        final GatewayBootstrap gatewayBootstrap = new GatewayBootstrap();
        final RouteConfig route =
                buildRoute(new UpstreamConfig("http", "127.0.0.1", unreachablePort, 500, 500, 500));
        gatewayBootstrap.start(new GatewayServerConfig(0, 1024 * 1024, true, List.of(route)));

        try {
            final HttpResponse<String> response =
                    sendGet(gatewayBootstrap.boundPort(), "/api/failure");

            assertEquals(502, response.statusCode());
            assertTrue(response.body().contains("\"code\":\"UPSTREAM_UNAVAILABLE\""));
            assertTrue(response.body().contains("\"category\":\"UPSTREAM_ERROR\""));
        } finally {
            gatewayBootstrap.stop();
        }
    }

    @Test
    void shouldReturn504WithinConfiguredTimeoutWhenUpstreamReadTimeout() throws Exception {
        final HttpServer slowServer = startSlowServer(1600, 200);
        final GatewayBootstrap gatewayBootstrap = new GatewayBootstrap();
        try {
            final int upstreamPort = slowServer.getAddress().getPort();
            final RouteConfig route =
                    buildRoute(
                            new UpstreamConfig("http", "127.0.0.1", upstreamPort, 500, 1000, 1000));
            gatewayBootstrap.start(new GatewayServerConfig(0, 1024 * 1024, true, List.of(route)));

            final long startNanos = System.nanoTime();
            final HttpResponse<String> response =
                    sendGet(gatewayBootstrap.boundPort(), "/api/timeout");
            final long latencyMs = (System.nanoTime() - startNanos) / 1_000_000;

            assertEquals(504, response.statusCode());
            assertTrue(response.body().contains("\"code\":\"UPSTREAM_TIMEOUT\""));
            assertTrue(latencyMs >= 900, "latencyMs=" + latencyMs);
            assertTrue(latencyMs <= 1400, "latencyMs=" + latencyMs);
        } finally {
            gatewayBootstrap.stop();
            slowServer.stop(0);
        }
    }

    private static RouteConfig buildRoute(final UpstreamConfig upstreamConfig) {
        return new RouteConfig(
                "route-failure",
                100,
                MatchType.PREFIX,
                "/api/",
                HostRewriteMode.REWRITE,
                upstreamConfig);
    }

    private static HttpResponse<String> sendGet(final int gatewayPort, final String path)
            throws Exception {
        final HttpClient client = HttpClient.newHttpClient();
        final HttpRequest request =
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + gatewayPort + path))
                        .GET()
                        .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static HttpServer startSlowServer(final int delayMs, final int statusCode)
            throws IOException {
        final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    try {
                        Thread.sleep(delayMs);
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                    }
                    final byte[] response = "slow-upstream".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(statusCode, response.length);
                    exchange.getResponseBody().write(response);
                    exchange.close();
                });
        server.start();
        return server;
    }

    private static int findUnusedPort() throws IOException {
        try (java.net.ServerSocket socket = new java.net.ServerSocket()) {
            socket.bind(new InetSocketAddress("127.0.0.1", 0));
            return socket.getLocalPort();
        }
    }
}
