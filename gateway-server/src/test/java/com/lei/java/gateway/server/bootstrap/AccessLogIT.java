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
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import com.lei.java.gateway.server.config.GatewayServerConfig;
import com.lei.java.gateway.server.config.HostRewriteMode;
import com.lei.java.gateway.server.config.MatchType;
import com.lei.java.gateway.server.config.RouteConfig;
import com.lei.java.gateway.server.config.UpstreamConfig;
import com.sun.net.httpserver.HttpServer;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

class AccessLogIT {

    @Test
    void shouldLogRequiredAccessFieldsWhenProxySuccess() throws Exception {
        final HttpServer upstreamServer = startUpstreamServer();
        final Logger accessLogger =
                (Logger) LoggerFactory.getLogger(DefaultHttpServerHandler.class);
        final ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        accessLogger.addAppender(appender);

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

            final HttpResponse<String> response =
                    HttpClient.newHttpClient()
                            .send(
                                    HttpRequest.newBuilder(
                                                    URI.create(
                                                            "http://127.0.0.1:"
                                                                    + gatewayBootstrap.boundPort()
                                                                    + "/api/ping"))
                                            .GET()
                                            .build(),
                                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            assertEquals(200, response.statusCode());

            final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            String matched = "";
            while (System.nanoTime() < deadline) {
                matched =
                        appender.list.stream()
                                .map(ILoggingEvent::getFormattedMessage)
                                .filter(msg -> msg.contains("uri=/api/ping"))
                                .findFirst()
                                .orElse("");
                if (!matched.isEmpty()) {
                    break;
                }
                TimeUnit.MILLISECONDS.sleep(20);
            }

            assertTrue(!matched.isEmpty(), "expected access log for /api/ping");
            assertTrue(matched.contains("traceId="), matched);
            assertTrue(matched.contains("status=200"), matched);
            assertTrue(matched.contains("latencyMs="), matched);
            assertTrue(matched.contains("routeId=route-api"), matched);
            assertTrue(matched.contains("upstream=http://127.0.0.1:" + upstreamPort), matched);
        } finally {
            gatewayBootstrap.stop();
            upstreamServer.stop(0);
            accessLogger.detachAppender(appender);
            appender.stop();
        }
    }

    private static HttpServer startUpstreamServer() throws IOException {
        final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    final byte[] response = "upstream-ping".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, response.length);
                    exchange.getResponseBody().write(response);
                    exchange.close();
                });
        server.start();
        return server;
    }
}
