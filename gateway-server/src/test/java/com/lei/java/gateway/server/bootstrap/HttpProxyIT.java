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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.lei.java.gateway.server.config.GatewayServerConfig;
import com.lei.java.gateway.server.config.HostRewriteMode;
import com.lei.java.gateway.server.config.MatchType;
import com.lei.java.gateway.server.config.RouteConfig;
import com.lei.java.gateway.server.config.UpstreamConfig;
import com.sun.net.httpserver.HttpServer;

class HttpProxyIT {

    private static final int SIX_MB = 6 * 1024 * 1024;
    private static final int TWO_MB = 2 * 1024 * 1024;

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

    @Test
    void shouldStreamWhenContentLengthExceedsThresholdForRequestAndResponse() throws Exception {
        final byte[] largeRequestBody = "x".repeat(4096).getBytes(StandardCharsets.UTF_8);
        final byte[] largeResponseBody = "y".repeat(4096).getBytes(StandardCharsets.UTF_8);
        final AtomicReference<byte[]> upstreamReceivedBodyRef = new AtomicReference<>();

        final HttpServer upstreamServer =
                HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstreamServer.createContext(
                "/",
                exchange -> {
                    upstreamReceivedBodyRef.set(exchange.getRequestBody().readAllBytes());
                    exchange.sendResponseHeaders(200, largeResponseBody.length);
                    exchange.getResponseBody().write(largeResponseBody);
                    exchange.close();
                });
        upstreamServer.start();

        final GatewayBootstrap gatewayBootstrap = new GatewayBootstrap();
        try {
            final int upstreamPort = upstreamServer.getAddress().getPort();
            final RouteConfig route =
                    new RouteConfig(
                            "route-stream",
                            100,
                            MatchType.PREFIX,
                            "/api/",
                            HostRewriteMode.REWRITE,
                            new UpstreamConfig(
                                    "http", "127.0.0.1", upstreamPort, 1000, 1000, 1000));
            gatewayBootstrap.start(new GatewayServerConfig(0, 1024, true, List.of(route)));

            final HttpClient client = HttpClient.newHttpClient();
            final HttpRequest request =
                    HttpRequest.newBuilder(
                                    URI.create(
                                            "http://127.0.0.1:"
                                                    + gatewayBootstrap.boundPort()
                                                    + "/api/stream"))
                            .POST(HttpRequest.BodyPublishers.ofByteArray(largeRequestBody))
                            .header("Content-Type", "application/octet-stream")
                            .build();
            final HttpResponse<byte[]> response =
                    client.send(request, HttpResponse.BodyHandlers.ofByteArray());

            assertEquals(200, response.statusCode());
            assertArrayEquals(largeRequestBody, upstreamReceivedBodyRef.get());
            assertArrayEquals(largeResponseBody, response.body());
        } finally {
            gatewayBootstrap.stop();
            upstreamServer.stop(0);
        }
    }

    @Test
    void shouldStreamLargeRequestAndLargeResponseWhenOverDefaultThreshold() throws Exception {
        final byte[] largeRequestBody = new byte[SIX_MB];
        Arrays.fill(largeRequestBody, (byte) 'x');
        final byte[] largeResponseBody = new byte[SIX_MB];
        Arrays.fill(largeResponseBody, (byte) 'y');
        final AtomicReference<byte[]> upstreamReceivedBodyRef = new AtomicReference<>();

        final HttpServer upstreamServer =
                HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstreamServer.createContext(
                "/",
                exchange -> {
                    upstreamReceivedBodyRef.set(exchange.getRequestBody().readAllBytes());
                    exchange.sendResponseHeaders(200, largeResponseBody.length);
                    exchange.getResponseBody().write(largeResponseBody);
                    exchange.close();
                });
        upstreamServer.start();

        final GatewayBootstrap gatewayBootstrap = new GatewayBootstrap();
        try {
            final int upstreamPort = upstreamServer.getAddress().getPort();
            final RouteConfig route =
                    new RouteConfig(
                            "route-large",
                            100,
                            MatchType.PREFIX,
                            "/api/",
                            HostRewriteMode.REWRITE,
                            new UpstreamConfig(
                                    "http", "127.0.0.1", upstreamPort, 1000, 3000, 3000));
            gatewayBootstrap.start(
                    new GatewayServerConfig(0, 5 * 1024 * 1024, true, List.of(route)));

            final HttpClient client = HttpClient.newHttpClient();
            final HttpRequest request =
                    HttpRequest.newBuilder(
                                    URI.create(
                                            "http://127.0.0.1:"
                                                    + gatewayBootstrap.boundPort()
                                                    + "/api/large"))
                            .POST(HttpRequest.BodyPublishers.ofByteArray(largeRequestBody))
                            .header("Content-Type", "application/octet-stream")
                            .build();
            final HttpResponse<byte[]> response =
                    client.send(request, HttpResponse.BodyHandlers.ofByteArray());

            assertEquals(200, response.statusCode());
            assertArrayEquals(largeRequestBody, upstreamReceivedBodyRef.get());
            assertArrayEquals(largeResponseBody, response.body());
        } finally {
            gatewayBootstrap.stop();
            upstreamServer.stop(0);
        }
    }

    @Test
    void shouldForwardChunkedRequestFromRealFileWhenTransferEncodingChunked() throws Exception {
        final Path payloadFile = createPayloadFile(TWO_MB, (byte) 'z');
        final byte[] expectedBody = Files.readAllBytes(payloadFile);
        final AtomicReference<byte[]> upstreamReceivedBodyRef = new AtomicReference<>();
        final AtomicReference<String> transferEncodingRef = new AtomicReference<>();

        final HttpServer upstreamServer =
                HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstreamServer.createContext(
                "/",
                exchange -> {
                    transferEncodingRef.set(
                            exchange.getRequestHeaders().getFirst("Transfer-Encoding"));
                    upstreamReceivedBodyRef.set(exchange.getRequestBody().readAllBytes());
                    final byte[] response = "chunked-ok".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, response.length);
                    exchange.getResponseBody().write(response);
                    exchange.close();
                });
        upstreamServer.start();

        final GatewayBootstrap gatewayBootstrap = new GatewayBootstrap();
        try {
            final int upstreamPort = upstreamServer.getAddress().getPort();
            final RouteConfig route =
                    new RouteConfig(
                            "route-chunked",
                            100,
                            MatchType.PREFIX,
                            "/api/",
                            HostRewriteMode.REWRITE,
                            new UpstreamConfig(
                                    "http", "127.0.0.1", upstreamPort, 1000, 3000, 3000));
            gatewayBootstrap.start(new GatewayServerConfig(0, 1024, true, List.of(route)));

            final RawHttpResponse response =
                    sendChunkedFileRequest(
                            gatewayBootstrap.boundPort(), "/api/chunked", payloadFile);

            assertEquals(200, response.statusCode());
            assertEquals("chunked-ok", new String(response.body(), StandardCharsets.UTF_8));
            assertArrayEquals(expectedBody, upstreamReceivedBodyRef.get());
            assertNotNull(transferEncodingRef.get());
            assertTrue(transferEncodingRef.get().toLowerCase(Locale.ROOT).contains("chunked"));
        } finally {
            gatewayBootstrap.stop();
            upstreamServer.stop(0);
            Files.deleteIfExists(payloadFile);
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

    private static Path createPayloadFile(final int bytes, final byte fillByte) throws IOException {
        final Path payloadFile = Files.createTempFile("gateway-chunked-", ".bin");
        final byte[] block = new byte[8192];
        Arrays.fill(block, fillByte);
        int remaining = bytes;
        try (OutputStream outputStream =
                Files.newOutputStream(
                        payloadFile,
                        StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE)) {
            while (remaining > 0) {
                final int written = Math.min(remaining, block.length);
                outputStream.write(block, 0, written);
                remaining -= written;
            }
        }
        return payloadFile;
    }

    private static RawHttpResponse sendChunkedFileRequest(
            final int gatewayPort, final String path, final Path payloadFile) throws IOException {
        try (Socket socket = new Socket("127.0.0.1", gatewayPort)) {
            socket.setSoTimeout(15000);
            final OutputStream outputStream = socket.getOutputStream();
            outputStream.write(
                    ("POST "
                                    + path
                                    + " HTTP/1.1\r\n"
                                    + "Host: 127.0.0.1\r\n"
                                    + "Transfer-Encoding: chunked\r\n"
                                    + "Content-Type: application/octet-stream\r\n"
                                    + "Connection: close\r\n"
                                    + "\r\n")
                            .getBytes(StandardCharsets.US_ASCII));

            try (InputStream fileInputStream = Files.newInputStream(payloadFile)) {
                final byte[] buffer = new byte[4096];
                int read;
                while ((read = fileInputStream.read(buffer)) >= 0) {
                    if (read == 0) {
                        continue;
                    }
                    outputStream.write(
                            Integer.toHexString(read).getBytes(StandardCharsets.US_ASCII));
                    outputStream.write("\r\n".getBytes(StandardCharsets.US_ASCII));
                    outputStream.write(buffer, 0, read);
                    outputStream.write("\r\n".getBytes(StandardCharsets.US_ASCII));
                }
            }
            outputStream.write("0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            outputStream.flush();

            return parseRawHttpResponse(socket.getInputStream().readAllBytes());
        }
    }

    private static RawHttpResponse parseRawHttpResponse(final byte[] rawResponseBytes) {
        final String response = new String(rawResponseBytes, StandardCharsets.ISO_8859_1);
        final int headerEnd = response.indexOf("\r\n\r\n");
        final String statusLine = response.substring(0, response.indexOf("\r\n"));
        final String[] parts = statusLine.split(" ");
        final int statusCode = Integer.parseInt(parts[1]);
        final byte[] body =
                Arrays.copyOfRange(rawResponseBytes, headerEnd + 4, rawResponseBytes.length);
        return new RawHttpResponse(statusCode, body);
    }

    private record RawHttpResponse(int statusCode, byte[] body) {
        private RawHttpResponse {
            // no-op
        }
    }
}
