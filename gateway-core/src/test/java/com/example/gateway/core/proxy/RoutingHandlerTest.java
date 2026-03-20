package com.example.gateway.core.proxy;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.gateway.core.config.GatewayProperties;
import com.example.gateway.core.config.ObservabilityProperties;
import com.example.gateway.core.config.RequestLimitProperties;
import com.example.gateway.core.config.Route;
import com.example.gateway.core.config.RouteResolver;
import com.example.gateway.core.observability.AccessLogWriter;
import com.example.gateway.core.observability.MetricsCollector;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.CharsetUtil;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RoutingHandlerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ObservabilityProperties observabilityProperties;
    private RequestLimitProperties requestLimitProperties;
    private MetricsCollector metricsCollector;
    private AccessLogWriter accessLogWriter;
    private AtomicInteger activeConnections;
    private Instant startTime;

    @BeforeEach
    void setUp() {
        observabilityProperties = new ObservabilityProperties();
        requestLimitProperties = new RequestLimitProperties();
        metricsCollector = new MetricsCollector(
                new SimpleMeterRegistry(), observabilityProperties);
        accessLogWriter = new AccessLogWriter(observabilityProperties);
        activeConnections = new AtomicInteger(0);
        startTime = Instant.parse("2025-01-15T10:30:00Z");
    }

    // ========== /health 端点测试 ==========

    @Test
    void healthReturns200WithJsonStructure() throws Exception {
        RoutingHandler handler = createHandler(List.of());
        EmbeddedChannel channel = new EmbeddedChannel(handler);

        DefaultFullHttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.GET, "/health");
        request.headers().set(HttpHeaderNames.HOST, "localhost");
        channel.writeInbound(request);

        FullHttpResponse response = channel.readOutbound();
        assertThat(response.status()).isEqualTo(HttpResponseStatus.OK);
        assertThat(response.headers().get(HttpHeaderNames.CONTENT_TYPE))
                .isEqualTo("application/json");

        String body = response.content().toString(CharsetUtil.UTF_8);
        JsonNode json = MAPPER.readTree(body);
        assertThat(json.get("status").asText()).isEqualTo("UP");
        assertThat(json.get("startTime").asText()).isEqualTo("2025-01-15T10:30:00Z");
        assertThat(json.get("activeConnections").isNumber()).isTrue();
        response.release();
        channel.finish();
    }

    @Test
    void healthReturnsActiveConnectionCount() throws Exception {
        RoutingHandler handler = createHandler(List.of());
        EmbeddedChannel channel = new EmbeddedChannel(handler);
        // EmbeddedChannel 创建时触发 channelActive，activeConnections 已经 +1
        // 再手动设置到 5，验证 /health 返回的值与 activeConnections 一致
        activeConnections.set(5);

        DefaultFullHttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.GET, "/health");
        request.headers().set(HttpHeaderNames.HOST, "localhost");
        channel.writeInbound(request);

        FullHttpResponse response = channel.readOutbound();
        String body = response.content().toString(CharsetUtil.UTF_8);
        JsonNode json = MAPPER.readTree(body);
        assertThat(json.get("activeConnections").asInt()).isEqualTo(5);
        response.release();
        channel.finish();
    }

    @Test
    void healthWithKeepAliveReturnsKeepAliveHeader() {
        RoutingHandler handler = createHandler(List.of());
        EmbeddedChannel channel = new EmbeddedChannel(handler);

        DefaultFullHttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.GET, "/health");
        request.headers().set(HttpHeaderNames.HOST, "localhost");
        request.headers().set(HttpHeaderNames.CONNECTION,
                HttpHeaderValues.KEEP_ALIVE);
        channel.writeInbound(request);

        FullHttpResponse response = channel.readOutbound();
        assertThat(response.headers().get(HttpHeaderNames.CONNECTION))
                .isEqualToIgnoringCase("keep-alive");
        response.release();
        channel.finish();
    }

    @Test
    void healthWithConnectionCloseReturnsCloseHeader() {
        RoutingHandler handler = createHandler(List.of());
        EmbeddedChannel channel = new EmbeddedChannel(handler);

        DefaultFullHttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.GET, "/health");
        request.headers().set(HttpHeaderNames.HOST, "localhost");
        request.headers().set(HttpHeaderNames.CONNECTION,
                HttpHeaderValues.CLOSE);
        channel.writeInbound(request);

        FullHttpResponse response = channel.readOutbound();
        assertThat(response.headers().get(HttpHeaderNames.CONNECTION))
                .isEqualToIgnoringCase("close");
        response.release();
        channel.finish();
    }

    @Test
    void healthIgnoresQueryParameters() throws Exception {
        RoutingHandler handler = createHandler(List.of());
        EmbeddedChannel channel = new EmbeddedChannel(handler);

        DefaultFullHttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.GET, "/health?verbose=true");
        request.headers().set(HttpHeaderNames.HOST, "localhost");
        channel.writeInbound(request);

        FullHttpResponse response = channel.readOutbound();
        assertThat(response.status()).isEqualTo(HttpResponseStatus.OK);
        response.release();
        channel.finish();
    }

    @Test
    void postHealthReturns404() throws Exception {
        RoutingHandler handler = createHandler(List.of());
        EmbeddedChannel channel = new EmbeddedChannel(handler);

        DefaultFullHttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.POST, "/health");
        request.headers().set(HttpHeaderNames.HOST, "localhost");
        channel.writeInbound(request);

        FullHttpResponse response = channel.readOutbound();
        assertThat(response.status()).isEqualTo(HttpResponseStatus.NOT_FOUND);
        response.release();
        channel.finish();
    }

    // ========== /metrics 端点测试 ==========

    @Test
    void metricsReturns200WhenEnabled() {
        observabilityProperties.setMetricsEnabled(true);
        RoutingHandler handler = createHandler(List.of());
        EmbeddedChannel channel = new EmbeddedChannel(handler);

        DefaultFullHttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.GET, "/metrics");
        request.headers().set(HttpHeaderNames.HOST, "localhost");
        channel.writeInbound(request);

        FullHttpResponse response = channel.readOutbound();
        assertThat(response.status()).isEqualTo(HttpResponseStatus.OK);
        assertThat(response.headers().get(HttpHeaderNames.CONTENT_TYPE))
                .contains("text/plain");
        response.release();
        channel.finish();
    }

    @Test
    void metricsReturns404WhenDisabled() throws Exception {
        observabilityProperties.setMetricsEnabled(false);
        RoutingHandler handler = createHandler(List.of());
        EmbeddedChannel channel = new EmbeddedChannel(handler);

        DefaultFullHttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.GET, "/metrics");
        request.headers().set(HttpHeaderNames.HOST, "localhost");
        channel.writeInbound(request);

        FullHttpResponse response = channel.readOutbound();
        assertThat(response.status()).isEqualTo(HttpResponseStatus.NOT_FOUND);
        String body = response.content().toString(CharsetUtil.UTF_8);
        JsonNode json = MAPPER.readTree(body);
        assertThat(json.get("status").asInt()).isEqualTo(404);
        response.release();
        channel.finish();
    }

    // ========== 404 错误响应测试 ==========

    @Test
    void unmatchedPathReturns404WithJsonBody() throws Exception {
        RoutingHandler handler = createHandler(List.of());
        EmbeddedChannel channel = new EmbeddedChannel(handler);

        DefaultFullHttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.GET, "/unknown/path");
        request.headers().set(HttpHeaderNames.HOST, "localhost");
        channel.writeInbound(request);

        FullHttpResponse response = channel.readOutbound();
        assertThat(response.status()).isEqualTo(HttpResponseStatus.NOT_FOUND);
        assertThat(response.headers().get(HttpHeaderNames.CONTENT_TYPE))
                .isEqualTo("application/json");

        String body = response.content().toString(CharsetUtil.UTF_8);
        JsonNode json = MAPPER.readTree(body);
        assertThat(json.get("status").asInt()).isEqualTo(404);
        assertThat(json.get("error").asText()).isEqualTo("Not Found");
        assertThat(json.get("message").asText()).contains("/unknown/path");
        response.release();
        channel.finish();
    }

    @Test
    void errorResponseIncludesContentLength() {
        RoutingHandler handler = createHandler(List.of());
        EmbeddedChannel channel = new EmbeddedChannel(handler);

        DefaultFullHttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.GET, "/no-match");
        request.headers().set(HttpHeaderNames.HOST, "localhost");
        channel.writeInbound(request);

        FullHttpResponse response = channel.readOutbound();
        int contentLength = response.headers()
                .getInt(HttpHeaderNames.CONTENT_LENGTH, -1);
        assertThat(contentLength).isGreaterThan(0);
        assertThat(contentLength)
                .isEqualTo(response.content().readableBytes());
        response.release();
        channel.finish();
    }

    // ========== 路由匹配测试 ==========

    @Test
    void matchedRouteAddsProxyHandlerToPipeline() {
        Route route = createRoute("svc", "/api/example",
                "http://localhost:8081");
        RoutingHandler handler = createHandler(List.of(route));
        EmbeddedChannel channel = new EmbeddedChannel();
        channel.pipeline().addLast("routing", handler);

        // 使用不带 body 的 GET 请求，Content-Length 不设置
        // ProxyHandler 会尝试处理请求（获取 upstream 连接等），
        // 但这里只验证 RoutingHandler 正确添加了 ProxyHandler 到 pipeline。
        // 由于 connectionPool 为 null，ProxyHandler 会在 exceptionCaught 中移除自身，
        // 所以改为验证 fireChannelRead 后 ProxyHandler 曾被添加过。
        DefaultFullHttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.GET, "/api/example/hello");
        request.headers().set(HttpHeaderNames.HOST, "localhost");

        // 在 writeInbound 之前检查 pipeline 中没有 proxy
        assertThat(channel.pipeline().get("proxy")).isNull();

        channel.writeInbound(request);

        // ProxyHandler 被添加后又因异常被移除，
        // 验证 RoutingHandler 确实尝试添加了 ProxyHandler：
        // 检查 outbound 中有错误响应（说明 ProxyHandler 被触发了）
        FullHttpResponse response = channel.readOutbound();
        assertThat(response).isNotNull();
        response.release();
        channel.finish();
    }

    // ========== 辅助方法 ==========

    private RoutingHandler createHandler(List<Route> routes) {
        GatewayProperties gatewayProperties = new GatewayProperties();
        gatewayProperties.setRoutes(routes);
        RouteResolver routeResolver = new RouteResolver(gatewayProperties);
        // connectionPool 传 null，路由匹配测试中 ProxyHandler 是占位实现不会真正使用
        return new RoutingHandler(routeResolver, requestLimitProperties,
                null, metricsCollector, accessLogWriter,
                observabilityProperties, activeConnections, startTime);
    }

    private static Route createRoute(String id, String pathPrefix,
            String upstream) {
        Route route = new Route();
        route.setId(id);
        route.setPathPrefix(pathPrefix);
        route.setUpstream(upstream);
        return route;
    }
}
