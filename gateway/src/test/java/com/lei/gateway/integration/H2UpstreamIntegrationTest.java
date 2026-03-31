package com.lei.gateway.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

/**
 * H2 upstream 端到端集成测试。
 *
 * <p>验证 H1→H2→H1 转发、多并发请求多路复用、大 body chunked 转发、无 body 请求。
 *
 * <p>Requirements: 1.1, 1.2, 3.4, 5.1, 5.2, 5.3, 5.4, 6.1, 6.2, 6.3
 */
class H2UpstreamIntegrationTest extends IntegrationTestBase {

    /**
     * 端到端 H1→H2→H1 GET 转发验证。
     */
    @Test
    void getRequestForwardedViaH2() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(gatewayUri("/api/example/hello"))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(
                request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("Hello from upstream!");

        assertThat(upstreamServer.getReceivedRequests()).hasSize(1);
        var received = upstreamServer.getReceivedRequests().get(0);
        assertThat(received.method()).isEqualTo("GET");
        assertThat(received.uri()).isEqualTo("/api/example/hello");
    }

    /**
     * 端到端 H1→H2→H1 POST 带 body 转发验证。
     */
    @Test
    void postRequestWithBodyForwardedViaH2() throws Exception {
        String body = "h2-test-request-body";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(gatewayUri("/api/example/echo"))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", "text/plain")
                .build();

        HttpResponse<String> response = httpClient.send(
                request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo(body);

        var received = upstreamServer.getReceivedRequests().get(0);
        assertThat(received.method()).isEqualTo("POST");
        assertThat(new String(received.body(), StandardCharsets.UTF_8))
                .isEqualTo(body);
    }

    /**
     * 多并发请求验证（同一连接上多路复用）。
     *
     * <p>发送 10 个并发请求，验证全部成功返回 200，
     * upstream 收到 10 个请求。H2 多路复用下应共享少量连接。
     */
    @Test
    void concurrentRequestsMultiplexedOnSameConnection() throws Exception {
        int concurrency = 10;
        var futures = new ArrayList<CompletableFuture<HttpResponse<String>>>();
        for (int ii = 0; ii < concurrency; ii++) {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(gatewayUri("/api/example/hello"))
                    .GET()
                    .build();
            futures.add(httpClient.sendAsync(request,
                    HttpResponse.BodyHandlers.ofString()));
        }

        var responses = futures.stream()
                .map(CompletableFuture::join)
                .toList();

        assertThat(responses).allSatisfy(resp -> {
            assertThat(resp.statusCode()).isEqualTo(200);
            assertThat(resp.body()).isEqualTo("Hello from upstream!");
        });
        assertThat(upstreamServer.getReceivedRequests()).hasSize(concurrency);
    }

    /**
     * 大 body chunked 转发验证（100KB 响应）。
     */
    @Test
    void largeResponseBodyStreamedViaH2() throws Exception {
        byte[] largeBody = new byte[100 * 1024];
        for (int ii = 0; ii < largeBody.length; ii++) {
            largeBody[ii] = (byte) (ii % 256);
        }
        upstreamServer.setHandler(req -> {
            io.netty.buffer.ByteBuf content =
                    io.netty.buffer.Unpooled.wrappedBuffer(largeBody);
            var resp = new io.netty.handler.codec.http.DefaultFullHttpResponse(
                    io.netty.handler.codec.http.HttpVersion.HTTP_1_1,
                    io.netty.handler.codec.http.HttpResponseStatus.OK,
                    content);
            resp.headers().set(io.netty.handler.codec.http.HttpHeaderNames.CONTENT_TYPE,
                    "application/octet-stream");
            resp.headers().setInt(io.netty.handler.codec.http.HttpHeaderNames.CONTENT_LENGTH,
                    content.readableBytes());
            return resp;
        });

        HttpRequest request = HttpRequest.newBuilder()
                .uri(gatewayUri("/api/example/download"))
                .GET()
                .build();

        HttpResponse<byte[]> response = httpClient.send(
                request, HttpResponse.BodyHandlers.ofByteArray());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).hasSize(100 * 1024);
        assertThat(response.body()).isEqualTo(largeBody);
    }

    /**
     * 无 body 请求验证（HEADERS 帧带 END_STREAM）。
     */
    @Test
    void noBodyRequestSendsEndStreamWithHeaders() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(gatewayUri("/api/example/hello"))
                .DELETE()
                .build();

        HttpResponse<String> response = httpClient.send(
                request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        var received = upstreamServer.getReceivedRequests().get(0);
        assertThat(received.method()).isEqualTo("DELETE");
        assertThat(received.body()).isEmpty();
    }

    /**
     * 大 body 请求转发验证（10KB 请求体）。
     */
    @Test
    void largeRequestBodyForwardedViaH2() throws Exception {
        byte[] largeBody = new byte[10 * 1024];
        for (int ii = 0; ii < largeBody.length; ii++) {
            largeBody[ii] = (byte) ('A' + (ii % 26));
        }
        String bodyStr = new String(largeBody, StandardCharsets.UTF_8);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(gatewayUri("/api/example/echo"))
                .POST(HttpRequest.BodyPublishers.ofString(bodyStr))
                .header("Content-Type", "text/plain")
                .build();

        HttpResponse<String> response = httpClient.send(
                request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo(bodyStr);
    }

    /**
     * 响应头保留验证（H2→H1 转换不丢失自定义头）。
     */
    @Test
    void responseHeadersPreservedViaH2() throws Exception {
        upstreamServer.setHandler(req -> {
            io.netty.buffer.ByteBuf content =
                    io.netty.buffer.Unpooled.copiedBuffer("ok",
                            io.netty.util.CharsetUtil.UTF_8);
            var resp = new io.netty.handler.codec.http.DefaultFullHttpResponse(
                    io.netty.handler.codec.http.HttpVersion.HTTP_1_1,
                    io.netty.handler.codec.http.HttpResponseStatus.OK,
                    content);
            resp.headers().set("X-Custom-Header", "h2-test-value");
            resp.headers().set(io.netty.handler.codec.http.HttpHeaderNames.CONTENT_TYPE,
                    "text/plain");
            resp.headers().setInt(io.netty.handler.codec.http.HttpHeaderNames.CONTENT_LENGTH,
                    content.readableBytes());
            return resp;
        });

        HttpRequest request = HttpRequest.newBuilder()
                .uri(gatewayUri("/api/example/hello"))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(
                request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("ok");
        assertThat(response.headers().firstValue("X-Custom-Header"))
                .hasValue("h2-test-value");
    }
}
