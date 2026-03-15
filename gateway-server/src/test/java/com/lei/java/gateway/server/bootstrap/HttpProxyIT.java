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
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.lei.java.gateway.server.config.GatewayServerConfig;
import com.lei.java.gateway.server.config.HostRewriteMode;
import com.lei.java.gateway.server.config.MatchType;
import com.lei.java.gateway.server.config.RouteConfig;
import com.lei.java.gateway.server.config.UpstreamConfig;
import com.sun.net.httpserver.HttpServer;

class HttpProxyIT {

    @Test
    void shouldReturn200WhenGetApiPing() throws Exception {
        final AtomicReference<String> methodRef = new AtomicReference<>();
        final AtomicReference<String> pathRef = new AtomicReference<>();
        final AtomicReference<String> queryRef = new AtomicReference<>();
        final AtomicReference<String> bodyRef = new AtomicReference<>();

        final HttpServer upstreamServer =
                startUpstreamServer(methodRef, pathRef, queryRef, bodyRef);
        final GatewayBootstrap gatewayBootstrap = new GatewayBootstrap();
        try {
            final int upstreamPort = upstreamServer.getAddress().getPort();
            final RouteConfig route =
                    new RouteConfig(
                            "route-api",
                            100,
                            MatchType.PREFIX,
                            "/api/",
                            HostRewriteMode.REWRITE,
                            new UpstreamConfig(
                                    "http", "127.0.0.1", upstreamPort, 1000, 1000, 1000));
            gatewayBootstrap.start(new GatewayServerConfig(0, 1024 * 1024, true, List.of(route)));

            final HttpClient client = HttpClient.newHttpClient();
            final HttpRequest request =
                    HttpRequest.newBuilder(
                                    URI.create(
                                            "http://127.0.0.1:"
                                                    + gatewayBootstrap.boundPort()
                                                    + "/api/ping"))
                            .GET()
                            .build();
            final HttpResponse<String> response =
                    client.send(
                            request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            assertEquals(200, response.statusCode());
            assertEquals("upstream-ping", response.body());
            assertEquals("GET", methodRef.get());
            assertEquals("/api/ping", pathRef.get());
            assertNull(queryRef.get());
        } finally {
            gatewayBootstrap.stop();
            upstreamServer.stop(0);
        }
    }

    @Test
    void shouldForwardMethodPathQueryAndBodyToUpstream() throws Exception {
        final AtomicReference<String> methodRef = new AtomicReference<>();
        final AtomicReference<String> pathRef = new AtomicReference<>();
        final AtomicReference<String> queryRef = new AtomicReference<>();
        final AtomicReference<String> bodyRef = new AtomicReference<>();

        final HttpServer upstreamServer =
                startUpstreamServer(methodRef, pathRef, queryRef, bodyRef);
        final GatewayBootstrap gatewayBootstrap = new GatewayBootstrap();
        try {
            final int upstreamPort = upstreamServer.getAddress().getPort();
            final RouteConfig route =
                    new RouteConfig(
                            "route-api",
                            100,
                            MatchType.PREFIX,
                            "/api/",
                            HostRewriteMode.REWRITE,
                            new UpstreamConfig(
                                    "http", "127.0.0.1", upstreamPort, 1000, 1000, 1000));
            final GatewayServerConfig gatewayConfig =
                    new GatewayServerConfig(0, 1024 * 1024, true, List.of(route));
            gatewayBootstrap.start(gatewayConfig);

            final HttpClient client = HttpClient.newHttpClient();
            final URI uri =
                    URI.create(
                            "http://127.0.0.1:"
                                    + gatewayBootstrap.boundPort()
                                    + "/api/echo?type=ping");
            final HttpRequest request =
                    HttpRequest.newBuilder(uri)
                            .POST(HttpRequest.BodyPublishers.ofString("hello-upstream"))
                            .header("Content-Type", "text/plain")
                            .build();
            final HttpResponse<String> response =
                    client.send(
                            request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            assertEquals(201, response.statusCode());
            assertEquals("upstream-ok", response.body());
            assertEquals("ok", response.headers().firstValue("X-Upstream-Result").orElse(""));
            assertEquals("POST", methodRef.get());
            assertEquals("/api/echo", pathRef.get());
            assertEquals("type=ping", queryRef.get());
            assertEquals("hello-upstream", bodyRef.get());
        } finally {
            gatewayBootstrap.stop();
            upstreamServer.stop(0);
        }
    }

    private static HttpServer startUpstreamServer(
            final AtomicReference<String> methodRef,
            final AtomicReference<String> pathRef,
            final AtomicReference<String> queryRef,
            final AtomicReference<String> bodyRef)
            throws IOException {
        final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    methodRef.set(exchange.getRequestMethod());
                    pathRef.set(exchange.getRequestURI().getPath());
                    queryRef.set(exchange.getRequestURI().getQuery());
                    bodyRef.set(
                            new String(
                                    exchange.getRequestBody().readAllBytes(),
                                    StandardCharsets.UTF_8));
                    final byte[] response;
                    final int statusCode;
                    if ("/api/ping".equals(exchange.getRequestURI().getPath())) {
                        response = "upstream-ping".getBytes(StandardCharsets.UTF_8);
                        statusCode = 200;
                    } else {
                        response = "upstream-ok".getBytes(StandardCharsets.UTF_8);
                        statusCode = 201;
                    }
                    exchange.getResponseHeaders().add("X-Upstream-Result", "ok");
                    exchange.sendResponseHeaders(statusCode, response.length);
                    exchange.getResponseBody().write(response);
                    exchange.close();
                });
        server.start();
        return server;
    }
}
