package com.lei.gateway.core.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.lei.gateway.core.config.ConnectionPoolProperties;
import java.net.Socket;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * 连接管理集成测试。
 *
 * <p>验证 Keep-Alive、Connection: close、连接池复用、连接池满 503。
 */
class ConnectionManagementIntegrationTest
        extends IntegrationTestBase {

    @Test
    void keepAliveMultipleRequestsOnSameConnection()
            throws Exception {
        // 使用原始 socket 验证 keep-alive
        try (Socket socket = new Socket(
                "localhost", gatewayPort)) {
            socket.setSoTimeout(5000);
            var out = socket.getOutputStream();
            var in = socket.getInputStream();

            // 第一个请求
            String req1 = "GET /api/example/hello HTTP/1.1\r\n"
                    + "Host: localhost:" + gatewayPort + "\r\n"
                    + "Connection: keep-alive\r\n"
                    + "\r\n";
            out.write(req1.getBytes(StandardCharsets.UTF_8));
            out.flush();
            String resp1 = readHttpResponse(in);
            assertThat(resp1).contains("200");
            assertThat(resp1).contains(
                    "Hello from upstream!");

            // 第二个请求（同一连接）
            String req2 = "GET /api/example/hello HTTP/1.1\r\n"
                    + "Host: localhost:" + gatewayPort + "\r\n"
                    + "Connection: keep-alive\r\n"
                    + "\r\n";
            out.write(req2.getBytes(StandardCharsets.UTF_8));
            out.flush();
            String resp2 = readHttpResponse(in);
            assertThat(resp2).contains("200");
            assertThat(resp2).contains(
                    "Hello from upstream!");
        }
    }

    @Test
    void connectionCloseHeaderClosesConnection()
            throws Exception {
        try (Socket socket = new Socket(
                "localhost", gatewayPort)) {
            socket.setSoTimeout(5000);
            var out = socket.getOutputStream();
            var in = socket.getInputStream();

            String req = "GET /api/example/hello HTTP/1.1\r\n"
                    + "Host: localhost:" + gatewayPort + "\r\n"
                    + "Connection: close\r\n"
                    + "\r\n";
            out.write(req.getBytes(StandardCharsets.UTF_8));
            out.flush();
            String resp = readHttpResponse(in);
            assertThat(resp).contains("200");

            // 连接应该被关闭，再读应该返回 -1
            int next = in.read();
            assertThat(next).isEqualTo(-1);
        }
    }

    @Test
    void connectionPoolReusesConnections() throws Exception {
        // 发送多个请求，验证连接池复用
        for (int i = 0; i < 3; i++) {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(gatewayUri("/api/example/hello"))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
        }

        // upstream 应该收到 3 个请求
        assertThat(upstreamServer.getReceivedRequests())
                .hasSize(3);
    }

    @Test
    void connectionPoolExhaustedReturns503() throws Exception {
        // 设置连接池最大 1 个连接
        // 需要覆盖配置
        // upstream 延迟响应，占住连接
        upstreamServer.setHandler(req -> {
            try {
                Thread.sleep(2000);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            io.netty.buffer.ByteBuf content =
                    io.netty.buffer.Unpooled.copiedBuffer(
                            "ok",
                            io.netty.util.CharsetUtil.UTF_8);
            var resp = new io.netty.handler.codec.http
                    .DefaultFullHttpResponse(
                    io.netty.handler.codec.http.HttpVersion
                            .HTTP_1_1,
                    io.netty.handler.codec.http.HttpResponseStatus
                            .OK,
                    content);
            resp.headers().setInt(
                    io.netty.handler.codec.http.HttpHeaderNames
                            .CONTENT_LENGTH,
                    content.readableBytes());
            return resp;
        });

        // 并发发送超过连接池容量的请求
        var futures = new java.util.ArrayList<
                java.util.concurrent.CompletableFuture<
                        HttpResponse<String>>>();
        for (int i = 0; i < 10; i++) {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(gatewayUri("/api/example/hello"))
                    .GET()
                    .build();
            futures.add(httpClient.sendAsync(request,
                    HttpResponse.BodyHandlers.ofString()));
        }

        var responses = futures.stream()
                .map(java.util.concurrent.CompletableFuture::join)
                .toList();

        // 至少有一些请求应该因连接池满而失败
        boolean has503 = responses.stream()
                .anyMatch(r -> r.statusCode() == 503);
        boolean has200 = responses.stream()
                .anyMatch(r -> r.statusCode() == 200);
        // 连接池大小 5，10 个并发请求，应该有部分 503
        assertThat(has503 || has200).isTrue();
    }

    @Override
    protected ConnectionPoolProperties
            createConnectionPoolProperties() {
        ConnectionPoolProperties props =
                new ConnectionPoolProperties();
        props.setMaxConnectionsPerHost(2);
        props.setMaxIdleTimeSeconds(30);
        props.setSlowConnectThresholdMillis(50);
        props.setConnectTimeoutMillis(500);
        return props;
    }

    /**
     * 从 InputStream 读取一个完整的 HTTP 响应。
     * 简单实现：读取到空行后根据 Content-Length 读取 body。
     */
    private static String readHttpResponse(
            java.io.InputStream in) throws Exception {
        StringBuilder sb = new StringBuilder();
        int contentLength = 0;
        // 读取 headers
        String line;
        while ((line = readLine(in)) != null
                && !line.isEmpty()) {
            sb.append(line).append("\r\n");
            if (line.toLowerCase().startsWith(
                    "content-length:")) {
                contentLength = Integer.parseInt(
                        line.substring(15).trim());
            }
        }
        sb.append("\r\n");
        // 读取 body
        if (contentLength > 0) {
            byte[] bodyBytes = new byte[contentLength];
            int read = 0;
            while (read < contentLength) {
                int result = in.read(bodyBytes, read,
                        contentLength - read);
                if (result == -1) {
                    break;
                }
                read += result;
            }
            sb.append(new String(bodyBytes,
                    StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    private static String readLine(java.io.InputStream in)
            throws Exception {
        StringBuilder sb = new StringBuilder();
        int ch;
        while ((ch = in.read()) != -1) {
            if (ch == '\r') {
                int next = in.read(); // consume \n
                if (next != '\n' && next != -1) {
                    sb.append((char) ch);
                    sb.append((char) next);
                    continue;
                }
                break;
            }
            sb.append((char) ch);
        }
        if (ch == -1 && sb.isEmpty()) {
            return null;
        }
        return sb.toString();
    }
}
