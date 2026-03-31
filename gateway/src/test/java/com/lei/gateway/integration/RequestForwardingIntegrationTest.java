package com.lei.gateway.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.net.Socket;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * 端到端请求转发集成测试。
 *
 * <p>验证 GET/POST 请求转发、代理请求头、路由未匹配 404。
 */
class RequestForwardingIntegrationTest extends IntegrationTestBase {

    @Test
    void getRequestForwardedToUpstream() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(gatewayUri("/api/example/hello"))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(
                request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body())
                .isEqualTo("Hello from upstream!");
    }

    @Test
    void postRequestBodyForwardedToUpstream() throws Exception {
        String body = "test-request-body";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(gatewayUri("/api/example/echo"))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", "text/plain")
                .build();

        HttpResponse<String> response = httpClient.send(
                request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo(body);
    }

    @Test
    void proxyHeadersAddedToUpstreamRequest() throws Exception {
        // 使用 raw socket 设置自定义 Host 头
        try (Socket socket = new Socket(
                "localhost", gatewayPort)) {
            socket.setSoTimeout(5000);
            var out = socket.getOutputStream();
            String raw = "GET /api/example/hello HTTP/1.1\r\n"
                    + "Host: original-host.example.com\r\n"
                    + "Connection: close\r\n"
                    + "\r\n";
            out.write(raw.getBytes(StandardCharsets.UTF_8));
            out.flush();
            readFullResponse(socket.getInputStream());
        }

        assertThat(upstreamServer.getReceivedRequests())
                .hasSize(1);
        var received = upstreamServer.getReceivedRequests()
                .get(0);
        assertThat(received.headers().get("X-Forwarded-Host"))
                .isEqualTo("original-host.example.com");
        assertThat(received.headers().get("X-Forwarded-Proto"))
                .isEqualTo("http");
        assertThat(received.headers().get("X-Real-IP"))
                .isNotEmpty();
        assertThat(received.headers().get("X-Forwarded-For"))
                .isNotEmpty();
        assertThat(received.headers().get("Host"))
                .contains("localhost");
    }

    @Test
    void existingXForwardedForIsAppended() throws Exception {
        try (Socket socket = new Socket(
                "localhost", gatewayPort)) {
            socket.setSoTimeout(5000);
            var out = socket.getOutputStream();
            String raw = "GET /api/example/hello HTTP/1.1\r\n"
                    + "Host: localhost:" + gatewayPort + "\r\n"
                    + "X-Forwarded-For: 10.0.0.1\r\n"
                    + "Connection: close\r\n"
                    + "\r\n";
            out.write(raw.getBytes(StandardCharsets.UTF_8));
            out.flush();
            readFullResponse(socket.getInputStream());
        }

        var received = upstreamServer.getReceivedRequests()
                .get(0);
        String xff = received.headers().get("X-Forwarded-For");
        assertThat(xff).startsWith("10.0.0.1, ");
    }

    @Test
    void unmatchedRouteReturns404() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(gatewayUri("/unknown/path"))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(
                request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).contains("\"status\":404");
        assertThat(response.body()).contains("Not Found");
    }

    @Test
    void responseHeadersPreservedFromUpstream() throws Exception {
        upstreamServer.setHandler(req -> {
            io.netty.buffer.ByteBuf content =
                    io.netty.buffer.Unpooled.copiedBuffer(
                            "custom",
                            io.netty.util.CharsetUtil.UTF_8);
            var resp = new io.netty.handler.codec.http
                    .DefaultFullHttpResponse(
                    io.netty.handler.codec.http.HttpVersion
                            .HTTP_1_1,
                    io.netty.handler.codec.http
                            .HttpResponseStatus.OK,
                    content);
            resp.headers().set(
                    "X-Custom-Header", "custom-value");
            resp.headers().set(
                    io.netty.handler.codec.http
                            .HttpHeaderNames.CONTENT_TYPE,
                    "text/plain");
            resp.headers().setInt(
                    io.netty.handler.codec.http
                            .HttpHeaderNames.CONTENT_LENGTH,
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
        assertThat(response.body()).isEqualTo("custom");
        assertThat(response.headers()
                .firstValue("X-Custom-Header"))
                .hasValue("custom-value");
    }

    @Test
    void requestMethodPreservedInForwarding() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(gatewayUri("/api/example/echo"))
                .method("PUT",
                        HttpRequest.BodyPublishers.ofString(
                                "data"))
                .header("Content-Type", "text/plain")
                .build();

        httpClient.send(request,
                HttpResponse.BodyHandlers.ofString());

        var received = upstreamServer.getReceivedRequests()
                .get(0);
        assertThat(received.method()).isEqualTo("PUT");
    }

    /** 读取完整 HTTP 响应（简单实现）。 */
    private static String readFullResponse(InputStream in)
            throws Exception {
        byte[] buf = new byte[8192];
        StringBuilder sb = new StringBuilder();
        int read;
        while ((read = in.read(buf)) != -1) {
            sb.append(new String(buf, 0, read,
                    StandardCharsets.UTF_8));
            if (sb.toString().contains("\r\n\r\n")) {
                break;
            }
        }
        // 继续读 body（如果有 content-length）
        String headers = sb.toString();
        int clIdx = headers.toLowerCase(java.util.Locale.ROOT)
                .indexOf("content-length:");
        if (clIdx >= 0) {
            int nlIdx = headers.indexOf("\r\n", clIdx);
            int cl = Integer.parseInt(
                    headers.substring(clIdx + 15, nlIdx)
                            .trim());
            int bodyStart = headers.indexOf("\r\n\r\n") + 4;
            int bodyRead = headers.length() - bodyStart;
            while (bodyRead < cl) {
                read = in.read(buf);
                if (read == -1) {
                    break;
                }
                sb.append(new String(buf, 0, read,
                        StandardCharsets.UTF_8));
                bodyRead += read;
            }
        }
        return sb.toString();
    }
}
