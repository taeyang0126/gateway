package com.lei.gateway.core.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.lei.gateway.core.config.ObservabilityProperties;
import com.lei.gateway.core.config.RequestLimitProperties;
import com.lei.gateway.core.config.Route;
import com.lei.gateway.core.observability.AccessLogWriter;
import com.lei.gateway.core.observability.MetricsCollector;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 测试 UpstreamResponseHandler 对 1xx 中间响应的处理逻辑。
 */
class UpstreamResponseHandlerTest {

    private UpstreamConnectionPool connectionPool;
    private MetricsCollector metricsCollector;
    private AccessLogWriter accessLogWriter;

    @BeforeEach
    void setUp() {
        connectionPool = mock(UpstreamConnectionPool.class);
        metricsCollector = mock(MetricsCollector.class);
        accessLogWriter = mock(AccessLogWriter.class);
    }

    /**
     * 客户端带 Expect: 100-continue，上游回 100 → 网关应丢弃，不转发给客户端。
     * 当前策略：去掉 Expect 头转发给上游，丢弃所有 1xx 中间响应。
     */
    @Test
    void upstreamReturns100_clientExpectsContinue_shouldDiscard()
            throws Exception {
        EmbeddedChannel clientChannel = buildClientChannel(true);
        ChannelHandlerContext clientCtx = clientChannel.pipeline().firstContext();
        ProxyHandler proxyHandler = getProxyHandler(clientChannel);

        Object upstreamHandler = buildUpstreamResponseHandler(clientCtx, proxyHandler);
        EmbeddedChannel upstreamChannel = new EmbeddedChannel(
                (io.netty.channel.ChannelHandler) upstreamHandler);

        DefaultFullHttpResponse response100 = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, HttpResponseStatus.CONTINUE);
        upstreamChannel.writeInbound(response100);

        // 网关丢弃 1xx，客户端不应收到任何消息
        assertThat((Object) clientChannel.readOutbound()).isNull();
        assertThat(getResponseStatusCode(proxyHandler)).isZero();

        clientChannel.finish();
        upstreamChannel.finish();
    }

    /**
     * 客户端未带 Expect: 100-continue，上游主动发 100 → 网关应丢弃，不转发。
     */
    @Test
    void upstreamReturns100_clientNotExpecting_shouldDiscard()
            throws Exception {
        EmbeddedChannel clientChannel = buildClientChannel(false);
        ChannelHandlerContext clientCtx = clientChannel.pipeline().firstContext();
        ProxyHandler proxyHandler = getProxyHandler(clientChannel);

        Object upstreamHandler = buildUpstreamResponseHandler(clientCtx, proxyHandler);
        EmbeddedChannel upstreamChannel = new EmbeddedChannel(
                (io.netty.channel.ChannelHandler) upstreamHandler);

        DefaultFullHttpResponse response100 = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, HttpResponseStatus.CONTINUE);
        upstreamChannel.writeInbound(response100);

        // 客户端 channel 不应收到任何消息
        assertThat((Object) clientChannel.readOutbound()).isNull();
        assertThat(getResponseStatusCode(proxyHandler)).isZero();

        clientChannel.finish();
        upstreamChannel.finish();
    }

    /**
     * 客户端带 Expect: 100-continue，网关去掉 Expect 头直接转发给上游，
     * 不主动回 100，上游直接发 200，客户端应收到 200。
     */
    @Test
    void expectContinue_gatewayStripsHeader_upstreamReturns200()
            throws Exception {
        EmbeddedChannel clientChannel = buildClientChannel(true);

        // 网关不主动回 100，客户端此时不应有任何输出
        assertThat((Object) clientChannel.readOutbound()).isNull();

        ChannelHandlerContext clientCtx = clientChannel.pipeline().firstContext();
        ProxyHandler proxyHandler = getProxyHandler(clientChannel);
        Object upstreamHandler = buildUpstreamResponseHandler(clientCtx, proxyHandler);
        EmbeddedChannel upstreamChannel = new EmbeddedChannel(
                (io.netty.channel.ChannelHandler) upstreamHandler);

        DefaultFullHttpResponse response200 = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        response200.headers().set(HttpHeaderNames.CONTENT_LENGTH, 0);
        upstreamChannel.writeInbound(response200);

        io.netty.handler.codec.http.HttpResponse out = clientChannel.readOutbound();
        assertThat(out).isNotNull();
        assertThat(out.status()).isEqualTo(HttpResponseStatus.OK);
        assertThat(getResponseStatusCode(proxyHandler)).isEqualTo(200);

        clientChannel.finish();
        upstreamChannel.finish();
    }

    /**
     * 正常 200 响应（无 1xx）→ 行为不变。
     */
    @Test
    void upstreamReturnsDirectly200_shouldForwardNormally() throws Exception {
        EmbeddedChannel clientChannel = buildClientChannel(false);
        ChannelHandlerContext clientCtx = clientChannel.pipeline().firstContext();
        ProxyHandler proxyHandler = getProxyHandler(clientChannel);

        Object upstreamHandler = buildUpstreamResponseHandler(clientCtx, proxyHandler);
        EmbeddedChannel upstreamChannel = new EmbeddedChannel(
                (io.netty.channel.ChannelHandler) upstreamHandler);

        DefaultFullHttpResponse response200 = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        response200.headers().set(HttpHeaderNames.CONTENT_LENGTH, 0);
        upstreamChannel.writeInbound(response200);

        io.netty.handler.codec.http.HttpResponse out = clientChannel.readOutbound();
        assertThat(out).isNotNull();
        assertThat(out.status()).isEqualTo(HttpResponseStatus.OK);
        assertThat(getResponseStatusCode(proxyHandler)).isEqualTo(200);

        clientChannel.finish();
        upstreamChannel.finish();
    }

    // ---- 辅助方法 ----

    /**
     * 构造一个已安装 ProxyHandler 的客户端 EmbeddedChannel。
     * expectContinue 通过请求头控制。
     */
    private EmbeddedChannel buildClientChannel(boolean expectContinue)
            throws Exception {
        Route route = new Route();
        route.setId("test");
        route.setPathPrefix("/api");
        route.setUpstream("http://localhost:8081");

        RequestLimitProperties limitConfig = new RequestLimitProperties();
        limitConfig.setMaxRequestSize(1024 * 1024L);

        // acquire 永远不完成，避免异步回调干扰测试
        when(connectionPool.acquire(anyString(), anyInt()))
                .thenReturn(new CompletableFuture<>());

        ProxyContext proxyCtx = new ProxyContext(limitConfig,
                connectionPool, metricsCollector, accessLogWriter,
                new ObservabilityProperties(), new InFlightRequestTracker());
        ProxyHandler proxyHandler = new ProxyHandler(route, proxyCtx);

        EmbeddedChannel channel = new EmbeddedChannel(proxyHandler);

        // 发送请求头，触发 handleHttpRequest，设置 expectContinue 字段
        DefaultHttpRequest request = new DefaultHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.POST, "/api/upload");
        request.headers().set(HttpHeaderNames.HOST, "localhost");
        if (expectContinue) {
            request.headers().set(HttpHeaderNames.EXPECT,
                    HttpHeaderValues.CONTINUE);
        }
        channel.writeInbound(request);
        channel.runPendingTasks();

        return channel;
    }

    private ProxyHandler getProxyHandler(EmbeddedChannel channel)
            throws Exception {
        // ProxyHandler 是 pipeline 的第一个 handler
        return (ProxyHandler) channel.pipeline().first();
    }

    /**
     * 通过反射构造内部类 UpstreamResponseHandler 实例。
     */
    private Object buildUpstreamResponseHandler(
            ChannelHandlerContext clientCtx, ProxyHandler proxyHandler)
            throws Exception {
        Class<?> handlerClass = null;
        for (Class<?> inner : ProxyHandler.class.getDeclaredClasses()) {
            if (inner.getSimpleName().equals("UpstreamResponseHandler")) {
                handlerClass = inner;
                break;
            }
        }
        assertThat(handlerClass).isNotNull();
        Constructor<?> ctor = handlerClass.getDeclaredConstructor(
                ChannelHandlerContext.class, ProxyHandler.class);
        ctor.setAccessible(true);
        return ctor.newInstance(clientCtx, proxyHandler);
    }

    private int getResponseStatusCode(ProxyHandler proxyHandler)
            throws Exception {
        Field field = ProxyHandler.class.getDeclaredField("responseStatusCode");
        field.setAccessible(true);
        return (int) field.get(proxyHandler);
    }
}
