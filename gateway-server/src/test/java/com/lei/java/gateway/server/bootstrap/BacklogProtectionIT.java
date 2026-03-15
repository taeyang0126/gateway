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

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import com.lei.java.gateway.server.config.GatewayServerConfig;
import com.lei.java.gateway.server.config.HostRewriteMode;
import com.lei.java.gateway.server.config.MatchType;
import com.lei.java.gateway.server.config.RouteConfig;
import com.lei.java.gateway.server.config.UpstreamConfig;
import com.sun.net.httpserver.HttpServer;

class BacklogProtectionIT {

    private static final Pattern HTTP_STATUS_PATTERN = Pattern.compile("HTTP/1.1\\s+(\\d{3})");

    @Test
    void shouldReturn503WhenPerRouteBacklogIsOverflowed() throws Exception {
        final HttpServer slowServer = startSlowServer(1200);
        final GatewayBootstrap gatewayBootstrap = new GatewayBootstrap();
        try {
            final int upstreamPort = slowServer.getAddress().getPort();
            final RouteConfig route =
                    new RouteConfig(
                            "route-overflow",
                            100,
                            MatchType.PREFIX,
                            "/api/",
                            HostRewriteMode.REWRITE,
                            new UpstreamConfig(
                                    "http", "127.0.0.1", upstreamPort, 1000, 3000, 3000));
            gatewayBootstrap.start(
                    new GatewayServerConfig(
                            0,
                            1024 * 1024,
                            true,
                            List.of(route),
                            true,
                            true,
                            List.of("127.0.0.1", "::1"),
                            2));

            final List<Integer> statusCodes =
                    sendPipelinedRequests(gatewayBootstrap.boundPort(), 3);
            final long status200Count = statusCodes.stream().filter(code -> code == 200).count();
            final long status503Count = statusCodes.stream().filter(code -> code == 503).count();
            assertTrue(status200Count >= 1, "expected at least one success response");
            assertTrue(status503Count >= 1, "expected at least one backlog overflow response");
        } finally {
            gatewayBootstrap.stop();
            slowServer.stop(0);
        }
    }

    private static List<Integer> sendPipelinedRequests(final int gatewayPort, final int count)
            throws IOException {
        final String requestBlock =
                "GET /api/slow HTTP/1.1\r\n"
                        + "Host: 127.0.0.1\r\n"
                        + "Connection: keep-alive\r\n"
                        + "\r\n";
        final byte[] allRequests = requestBlock.repeat(count).getBytes(StandardCharsets.UTF_8);
        try (Socket socket = new Socket("127.0.0.1", gatewayPort)) {
            socket.setSoTimeout((int) TimeUnit.SECONDS.toMillis(10));
            socket.getOutputStream().write(allRequests);
            socket.getOutputStream().flush();

            final StringBuilder responseText = new StringBuilder();
            final byte[] chunk = new byte[4096];
            while (countStatusLines(responseText) < count) {
                final int read = socket.getInputStream().read(chunk);
                if (read < 0) {
                    break;
                }
                responseText.append(new String(chunk, 0, read, StandardCharsets.UTF_8));
            }
            return extractStatusCodes(responseText.toString());
        }
    }

    private static List<Integer> extractStatusCodes(final String responseText) {
        final Matcher matcher = HTTP_STATUS_PATTERN.matcher(responseText);
        final java.util.ArrayList<Integer> statusCodes = new java.util.ArrayList<>();
        while (matcher.find()) {
            statusCodes.add(Integer.parseInt(matcher.group(1)));
        }
        return statusCodes;
    }

    private static int countStatusLines(final CharSequence responseText) {
        final Matcher matcher = HTTP_STATUS_PATTERN.matcher(responseText);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    private static HttpServer startSlowServer(final int delayMs) throws IOException {
        final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(delayMs));
                    final byte[] response = "ok".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, response.length);
                    exchange.getResponseBody().write(response);
                    exchange.close();
                });
        server.start();
        return server;
    }
}
