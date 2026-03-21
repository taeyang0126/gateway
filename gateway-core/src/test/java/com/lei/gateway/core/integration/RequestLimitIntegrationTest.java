package com.lei.gateway.core.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.lei.gateway.core.config.GatewayProperties;
import com.lei.gateway.core.config.RequestLimitProperties;
import com.lei.gateway.core.config.Route;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 请求限制与超时集成测试。
 *
 * <p>验证 Content-Length 预检、字节累计超限、路由级别覆盖、超时处理。
 */
class RequestLimitIntegrationTest extends IntegrationTestBase {

    @Override
    protected RequestLimitProperties createRequestLimitProperties() {
        RequestLimitProperties props = new RequestLimitProperties();
        props.setMaxRequestSize(1024L); // 1KB 全局限制
        props.setTimeoutSeconds(3);
        props.setIdleTimeoutSeconds(2); // 测试用短 idle 超时，触发 504
        return props;
    }

    @Override
    protected GatewayProperties createGatewayProperties() {
        GatewayProperties props = new GatewayProperties();
        props.setPort(0);
        List<Route> routes = new ArrayList<>();
        routes.add(createRoute("example-service",
                "/api/example/**",
                "http://localhost:" + upstreamPort));
        // 路由级别限制 512 字节
        Route limited = createRoute("limited-service",
                "/api/limited/**",
                "http://localhost:" + upstreamPort);
        limited.setMaxRequestSize(512L);
        routes.add(limited);
        // 路由级别超时 1 秒
        Route slowRoute = createRoute("slow-service",
                "/api/slow/**",
                "http://localhost:" + upstreamPort);
        slowRoute.setTimeoutSeconds(1);
        routes.add(slowRoute);
        props.setRoutes(routes);
        return props;
    }

    @Test
    void contentLengthExceedsGlobalLimitReturns413() throws Exception {
        // 2KB body，超过全局 1KB 限制
        String body = "x".repeat(2048);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(gatewayUri("/api/example/echo"))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", "text/plain")
                .build();

        HttpResponse<String> response = httpClient.send(
                request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(response.body()).contains("too large");
    }

    @Test
    void routeLevelMaxRequestSizeOverridesGlobal()
            throws Exception {
        // 800 字节：超过路由级别 512 但在全局 1024 内
        String body = "x".repeat(800);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(gatewayUri("/api/limited/echo"))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", "text/plain")
                .build();

        HttpResponse<String> response = httpClient.send(
                request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(413);
    }

    @Test
    void requestWithinLimitSucceeds() throws Exception {
        // 500 字节：在全局 1KB 限制内
        String body = "x".repeat(500);
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
    void upstreamTimeoutReturns504() throws Exception {
        // upstream 延迟响应，超过路由级别 1 秒超时
        upstreamServer.setHandler(req -> {
            try {
                Thread.sleep(3000);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            io.netty.buffer.ByteBuf content =
                    io.netty.buffer.Unpooled.copiedBuffer(
                            "late",
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

        HttpRequest request = HttpRequest.newBuilder()
                .uri(gatewayUri("/api/slow/test"))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(
                request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(504);
    }
}
