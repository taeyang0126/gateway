package com.lei.gateway.core.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lei.gateway.core.config.ObservabilityProperties;
import com.lei.gateway.core.config.RequestLimitProperties;
import com.lei.gateway.core.config.Route;
import com.lei.gateway.core.observability.AccessLogWriter;
import com.lei.gateway.core.observability.MetricsCollector;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.CharsetUtil;
import io.netty.util.ReferenceCountUtil;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * ProxyHandler 单元测试：Content-Length 预检大小限制。
 */
class ProxyHandlerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RequestLimitProperties limitConfig;
    private UpstreamConnectionPool connectionPool;
    private MetricsCollector metricsCollector;
    private AccessLogWriter accessLogWriter;
    private ObservabilityProperties observabilityConfig;

    @BeforeEach
    void setUp() {
        limitConfig = new RequestLimitProperties();
        limitConfig.setMaxRequestSize(1024); // 1KB for testing
        connectionPool = mock(UpstreamConnectionPool.class);
        metricsCollector = mock(MetricsCollector.class);
        accessLogWriter = mock(AccessLogWriter.class);
        observabilityConfig = new ObservabilityProperties();
    }

    @Test
    void contentLengthExceedsGlobalLimitReturns413() throws Exception {
        Route route = createRoute("svc", "/api", "http://localhost:8081");
        ProxyHandler handler = createHandler(route);
        EmbeddedChannel channel = new EmbeddedChannel(handler);

        DefaultHttpRequest request = new DefaultHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.POST, "/api/test");
        request.headers().set(HttpHeaderNames.HOST, "localhost");
        HttpUtil.setContentLength(request, 2048);
        channel.writeInbound(request);

        FullHttpResponse response = channel.readOutbound();
        assertThat(response).isNotNull();
        assertThat(response.status())
                .isEqualTo(HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE);

        String body = response.content().toString(CharsetUtil.UTF_8);
        JsonNode json = MAPPER.readTree(body);
        assertThat(json.get("status").asInt()).isEqualTo(413);
        assertThat(json.get("message").asText()).contains("too large");

        response.release();
        // 不应尝试获取 upstream 连接
        verify(connectionPool, never())
                .acquire(anyString(), anyInt());
        channel.finish();
    }

    @Test
    void contentLengthExceedsRouteLevelLimitReturns413() throws Exception {
        Route route = createRoute("svc", "/api", "http://localhost:8081");
        route.setMaxRequestSize(512L); // 路由级别 512 字节
        ProxyHandler handler = createHandler(route);
        EmbeddedChannel channel = new EmbeddedChannel(handler);

        DefaultHttpRequest request = new DefaultHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.POST, "/api/test");
        request.headers().set(HttpHeaderNames.HOST, "localhost");
        HttpUtil.setContentLength(request, 600);
        channel.writeInbound(request);

        FullHttpResponse response = channel.readOutbound();
        assertThat(response).isNotNull();
        assertThat(response.status())
                .isEqualTo(HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE);

        response.release();
        verify(connectionPool, never())
                .acquire(anyString(), anyInt());
        channel.finish();
    }

    @Test
    void contentLengthWithinLimitDoesNotReturn413() throws Exception {
        Route route = createRoute("svc", "/api", "http://localhost:8081");
        // mock acquire 返回失败的 Future，验证预检通过后确实尝试了获取连接
        when(connectionPool.acquire("localhost", 8081))
                .thenReturn(CompletableFuture.failedFuture(
                        new RuntimeException("expected in test")));
        ProxyHandler handler = createHandler(route);
        EmbeddedChannel channel = new EmbeddedChannel(handler);

        DefaultHttpRequest request = new DefaultHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.POST, "/api/test");
        request.headers().set(HttpHeaderNames.HOST, "localhost");
        HttpUtil.setContentLength(request, 512);
        channel.writeInbound(request);

        // 异步回调需要在 EventLoop 上执行
        channel.runPendingTasks();

        // 预检通过，应该尝试获取连接（会因 mock 异常返回 502）
        verify(connectionPool).acquire("localhost", 8081);

        FullHttpResponse response = channel.readOutbound();
        assertThat(response).isNotNull();
        // 502 因为 mock 返回失败 Future，但不是 413
        assertThat(response.status())
                .isEqualTo(HttpResponseStatus.BAD_GATEWAY);
        response.release();
        channel.finish();
    }

    @Test
    void routeLevelMaxRequestSizeOverridesGlobal() throws Exception {
        // 全局 1024，路由级别 2048
        Route route = createRoute("svc", "/api", "http://localhost:8081");
        route.setMaxRequestSize(2048L);
        when(connectionPool.acquire("localhost", 8081))
                .thenReturn(CompletableFuture.failedFuture(
                        new RuntimeException("expected in test")));
        ProxyHandler handler = createHandler(route);
        EmbeddedChannel channel = new EmbeddedChannel(handler);

        // 1500 字节：超过全局 1024 但在路由级别 2048 内
        DefaultHttpRequest request = new DefaultHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.POST, "/api/test");
        request.headers().set(HttpHeaderNames.HOST, "localhost");
        HttpUtil.setContentLength(request, 1500);
        channel.writeInbound(request);

        channel.runPendingTasks();

        // 应该通过预检，尝试获取连接
        verify(connectionPool).acquire("localhost", 8081);

        FullHttpResponse response = channel.readOutbound();
        assertThat(response).isNotNull();
        assertThat(response.status()).isNotEqualTo(
                HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE);
        response.release();
        channel.finish();
    }

    @Test
    void noContentLengthHeaderPassesPrecheck() throws Exception {
        Route route = createRoute("svc", "/api", "http://localhost:8081");
        when(connectionPool.acquire("localhost", 8081))
                .thenReturn(CompletableFuture.failedFuture(
                        new RuntimeException("expected in test")));
        ProxyHandler handler = createHandler(route);
        EmbeddedChannel channel = new EmbeddedChannel(handler);

        // 不设置 Content-Length
        DefaultHttpRequest request = new DefaultHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.GET, "/api/test");
        request.headers().set(HttpHeaderNames.HOST, "localhost");
        channel.writeInbound(request);

        channel.runPendingTasks();

        // 无 Content-Length 应通过预检
        verify(connectionPool).acquire("localhost", 8081);

        FullHttpResponse response = channel.readOutbound();
        assertThat(response).isNotNull();
        response.release();
        channel.finish();
    }

    @Test
    void nonLastContentShouldFlushToUpstreamImmediately() {
        Route route = createRoute("svc", "/api", "http://localhost:8081");
        EmbeddedChannel upstreamChannel = new EmbeddedChannel();
        when(connectionPool.acquire("localhost", 8081))
                .thenReturn(CompletableFuture.completedFuture(upstreamChannel));
        ProxyHandler handler = createHandler(route);
        EmbeddedChannel clientChannel = new EmbeddedChannel(handler);

        DefaultHttpRequest request = new DefaultHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.POST, "/api/test");
        request.headers().set(HttpHeaderNames.HOST, "localhost");
        clientChannel.writeInbound(request);
        clientChannel.runPendingTasks();

        // 请求头使用 write，不会立即出现在 outbound（尚未 flush）
        assertThat((Object) upstreamChannel.readOutbound()).isNull();

        DefaultHttpContent chunk = new DefaultHttpContent(
                Unpooled.copiedBuffer("abc", CharsetUtil.UTF_8));
        clientChannel.writeInbound(chunk);

        Object first = upstreamChannel.readOutbound();
        Object second = upstreamChannel.readOutbound();
        assertThat(first).isNotNull();
        assertThat(second).isInstanceOf(HttpContent.class);

        ReferenceCountUtil.release(first);
        ReferenceCountUtil.release(second);
        clientChannel.finishAndReleaseAll();
        upstreamChannel.finishAndReleaseAll();
    }

    @Test
    void bufferingContentShouldNotRetainExtraReference() {
        Route route = createRoute("svc", "/api", "http://localhost:8081");
        CompletableFuture<Channel> acquireFuture = new CompletableFuture<>();
        when(connectionPool.acquire("localhost", 8081))
                .thenReturn(acquireFuture);
        ProxyHandler handler = createHandler(route);
        EmbeddedChannel clientChannel = new EmbeddedChannel(handler);

        DefaultHttpRequest request = new DefaultHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.POST, "/api/test");
        request.headers().set(HttpHeaderNames.HOST, "localhost");
        clientChannel.writeInbound(request);

        DefaultHttpContent chunk = new DefaultHttpContent(
                Unpooled.copiedBuffer("abc", CharsetUtil.UTF_8));
        assertThat(chunk.refCnt()).isEqualTo(1);
        clientChannel.writeInbound(chunk);
        assertThat(chunk.refCnt()).isEqualTo(1);

        clientChannel.close();
        assertThat(chunk.refCnt()).isZero();
        clientChannel.finishAndReleaseAll();
    }

    private ProxyHandler createHandler(Route route) {
        return new ProxyHandler(route, limitConfig, connectionPool,
                metricsCollector, accessLogWriter, observabilityConfig);
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
