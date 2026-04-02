package com.lei.gateway.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lei.gateway.config.ObservabilityProperties;
import com.lei.gateway.config.RequestLimitProperties;
import com.lei.gateway.config.Route;
import com.lei.gateway.observability.AccessLogWriter;
import com.lei.gateway.observability.MetricsCollector;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lei.gateway.observability.TraceContextHandler;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.DefaultChannelId;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.DefaultLastHttpContent;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http2.DefaultHttp2DataFrame;
import io.netty.handler.codec.http2.DefaultHttp2HeadersFrame;
import io.netty.handler.codec.http2.Http2FrameCodec;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2TestHelper;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.util.Attribute;
import io.netty.util.CharsetUtil;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * ProxyHandler H2 模式单元测试。
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
        limitConfig.setMaxRequestSize(1024);
        connectionPool = mock(UpstreamConnectionPool.class);
        metricsCollector = mock(MetricsCollector.class);
        accessLogWriter = mock(AccessLogWriter.class);
        observabilityConfig = new ObservabilityProperties();
    }

    // ========================================================================
    // 辅助方法
    // ========================================================================

    private ProxyHandler createHandler(Route route) {
        InFlightRequestTracker inFlightTracker = new InFlightRequestTracker();
        ProxyContext proxyCtx = new ProxyContext(limitConfig, connectionPool,
                metricsCollector, accessLogWriter, observabilityConfig,
                inFlightTracker, null);
        return new ProxyHandler(route, proxyCtx);
    }

    private static Route createRoute(String id, String pathPrefix,
            String upstream) {
        Route route = new Route();
        route.setId(id);
        route.setPathPrefix(pathPrefix);
        route.setUpstream(upstream);
        return route;
    }

    /**
     * 创建一个 mock 的 H2 upstream channel。
     *
     * <p>使用完全 mock 的 Channel + mock EventLoop（execute 立即执行），
     * pipeline.get() 返回 mock 的 Http2FrameCodec 和 H2ResponseDemuxHandler。
     * 帧写入通过 channel.write() 捕获验证。
     */
    @SuppressWarnings("unchecked")
    private MockH2Upstream mockH2Upstream() {
        // mock ChannelPoolEntry
        ChannelPoolEntry entry = mock(ChannelPoolEntry.class);
        when(entry.nextStreamId()).thenReturn(1);

        // mock H2ResponseDemuxHandler
        H2ResponseDemuxHandler demux = mock(H2ResponseDemuxHandler.class);
        when(demux.tryReserveStream()).thenReturn(true);

        // mock Http2FrameCodec（通过 Http2TestHelper 访问 package-private newStream()）
        Http2FrameCodec codec = Http2TestHelper.mockFrameCodec(1);

        // mock Channel + Pipeline
        Channel channel = mock(Channel.class);
        ChannelPipeline pipeline = mock(ChannelPipeline.class);
        when(channel.pipeline()).thenReturn(pipeline);
        when(channel.isActive()).thenReturn(true);
        when(channel.id()).thenReturn(DefaultChannelId.newInstance());

        // mock ChannelPoolEntry attribute
        Attribute<ChannelPoolEntry> attr = mock(Attribute.class);
        when(attr.get()).thenReturn(entry);
        when(channel.attr(ChannelPoolEntry.POOL_ENTRY_KEY)).thenReturn(attr);

        // pipeline.get() 返回对应的 mock
        when(pipeline.get(H2ResponseDemuxHandler.class)).thenReturn(demux);
        when(pipeline.get(Http2FrameCodec.class)).thenReturn(codec);

        // mock EventLoop — execute() 立即执行 runnable
        io.netty.channel.EventLoop eventLoop = mock(io.netty.channel.EventLoop.class);
        when(channel.eventLoop()).thenReturn(eventLoop);
        when(eventLoop.inEventLoop()).thenReturn(true);
        org.mockito.Mockito.doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return null;
        }).when(eventLoop).execute(any(Runnable.class));

        // channel.write(Object) 返回成功的 ChannelFuture
        ChannelFuture successFuture = mock(ChannelFuture.class);
        when(successFuture.isSuccess()).thenReturn(true);
        when(successFuture.addListener(any())).thenReturn(successFuture);
        when(channel.write(any())).thenReturn(successFuture);
        when(channel.writeAndFlush(any())).thenReturn(successFuture);
        when(channel.flush()).thenReturn(channel);

        return new MockH2Upstream(channel, entry, demux);
    }

    private record MockH2Upstream(
            Channel channel,
            ChannelPoolEntry entry,
            H2ResponseDemuxHandler demux) {
    }

    // ========================================================================
    // Content-Length 预检
    // ========================================================================

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
        verify(connectionPool, never())
                .acquire(anyString(), anyInt());
        channel.finish();
    }

    @Test
    void contentLengthExceedsRouteLevelLimitReturns413() throws Exception {
        Route route = createRoute("svc", "/api", "http://localhost:8081");
        route.setMaxRequestSize(512L);
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
        channel.runPendingTasks();

        verify(connectionPool).acquire("localhost", 8081);

        FullHttpResponse response = channel.readOutbound();
        assertThat(response).isNotNull();
        assertThat(response.status())
                .isEqualTo(HttpResponseStatus.BAD_GATEWAY);
        response.release();
        channel.finish();
    }

    @Test
    void routeLevelMaxRequestSizeOverridesGlobal() throws Exception {
        Route route = createRoute("svc", "/api", "http://localhost:8081");
        route.setMaxRequestSize(2048L);
        when(connectionPool.acquire("localhost", 8081))
                .thenReturn(CompletableFuture.failedFuture(
                        new RuntimeException("expected in test")));
        ProxyHandler handler = createHandler(route);
        EmbeddedChannel channel = new EmbeddedChannel(handler);

        DefaultHttpRequest request = new DefaultHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.POST, "/api/test");
        request.headers().set(HttpHeaderNames.HOST, "localhost");
        HttpUtil.setContentLength(request, 1500);
        channel.writeInbound(request);
        channel.runPendingTasks();

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

        DefaultHttpRequest request = new DefaultHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.GET, "/api/test");
        request.headers().set(HttpHeaderNames.HOST, "localhost");
        channel.writeInbound(request);
        channel.runPendingTasks();

        verify(connectionPool).acquire("localhost", 8081);

        FullHttpResponse response = channel.readOutbound();
        assertThat(response).isNotNull();
        response.release();
        channel.finish();
    }

    // ========================================================================
    // H2 borrow → 流控检查 → write HEADERS → requite 流程
    // Requirements: 3.4, 4.3, 5.1
    // ========================================================================

    @Test
    void borrowAndWriteHeadersFlow() throws Exception {
        Route route = createRoute("svc", "/api", "http://localhost:8081");
        MockH2Upstream upstream = mockH2Upstream();
        when(connectionPool.acquire("localhost", 8081))
                .thenReturn(CompletableFuture.completedFuture(upstream.channel));
        ProxyHandler handler = createHandler(route);
        EmbeddedChannel clientChannel = new EmbeddedChannel(handler);

        DefaultHttpRequest request = new DefaultHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.GET, "/api/test");
        request.headers().set(HttpHeaderNames.HOST, "localhost");
        clientChannel.writeInbound(request);
        clientChannel.runPendingTasks();

        // 验证流控检查
        verify(upstream.demux).tryReserveStream();
        // 验证 streamId 分配
        verify(upstream.entry).nextStreamId();
        // 验证注册映射（streamId 由 frameStream.id() 返回 = 1）
        verify(upstream.demux).register(eq(1), eq(handler));
        // 验证 HEADERS 帧通过 channel.write() 写出
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(upstream.channel).write(captor.capture());
        assertThat(captor.getValue()).isInstanceOf(DefaultHttp2HeadersFrame.class);
        DefaultHttp2HeadersFrame headersFrame = (DefaultHttp2HeadersFrame) captor.getValue();
        assertThat(headersFrame.isEndStream()).isTrue();
        // 验证立即归还连接
        verify(connectionPool).release(upstream.channel);

        clientChannel.finishAndReleaseAll();
    }

    // ========================================================================
    // MAX_CONCURRENT_STREAMS 超限重试
    // Requirements: 4.3, 4.4
    // ========================================================================

    @Test
    void maxConcurrentStreamsExhausted_returns503AfterRetries() throws Exception {
        Route route = createRoute("svc", "/api", "http://localhost:8081");
        MockH2Upstream upstream = mockH2Upstream();
        // 所有连接都满
        when(upstream.demux.tryReserveStream()).thenReturn(false);
        when(connectionPool.acquire("localhost", 8081))
                .thenReturn(CompletableFuture.completedFuture(upstream.channel));
        ProxyHandler handler = createHandler(route);
        EmbeddedChannel clientChannel = new EmbeddedChannel(handler);

        DefaultHttpRequest request = new DefaultHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.GET, "/api/test");
        request.headers().set(HttpHeaderNames.HOST, "localhost");
        clientChannel.writeInbound(request);
        clientChannel.runPendingTasks();

        // 应重试 MAX_ACQUIRE_RETRIES+1 次 acquire（初始 1 次 + 重试 3 次 = 4 次）
        // 然后返回 503
        FullHttpResponse response = clientChannel.readOutbound();
        assertThat(response).isNotNull();
        assertThat(response.status()).isEqualTo(HttpResponseStatus.SERVICE_UNAVAILABLE);
        response.release();
        clientChannel.finish();
    }

    // ========================================================================
    // streamId 溢出时 retire 并重新 borrow
    // Requirements: 3.2
    // ========================================================================

    @Test
    void streamIdOverflow_retiresAndReacquires() throws Exception {
        Route route = createRoute("svc", "/api", "http://localhost:8081");
        MockH2Upstream overflowed = mockH2Upstream();
        when(overflowed.entry.nextStreamId()).thenReturn(-1);

        MockH2Upstream fresh = mockH2Upstream();
        when(fresh.entry.nextStreamId()).thenReturn(1);

        when(connectionPool.acquire("localhost", 8081))
                .thenReturn(CompletableFuture.completedFuture(overflowed.channel))
                .thenReturn(CompletableFuture.completedFuture(fresh.channel));
        ProxyHandler handler = createHandler(route);
        EmbeddedChannel clientChannel = new EmbeddedChannel(handler);

        DefaultHttpRequest request = new DefaultHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.GET, "/api/test");
        request.headers().set(HttpHeaderNames.HOST, "localhost");
        clientChannel.writeInbound(request);
        clientChannel.runPendingTasks();

        // 验证 retire 被调用
        verify(connectionPool).retire(overflowed.channel);
        // 验证第二次 acquire 成功后写 HEADERS 帧
        verify(fresh.channel).write(any(DefaultHttp2HeadersFrame.class));

        clientChannel.finishAndReleaseAll();
    }

    // ========================================================================
    // DATA 帧异步写入
    // Requirements: 5.2
    // ========================================================================

    @Test
    void postRequestWritesHeadersThenDataFrames() throws Exception {
        Route route = createRoute("svc", "/api", "http://localhost:8081");
        MockH2Upstream upstream = mockH2Upstream();
        when(connectionPool.acquire("localhost", 8081))
                .thenReturn(CompletableFuture.completedFuture(upstream.channel));
        ProxyHandler handler = createHandler(route);
        EmbeddedChannel clientChannel = new EmbeddedChannel(handler);

        DefaultHttpRequest request = new DefaultHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.POST, "/api/test");
        request.headers().set(HttpHeaderNames.HOST, "localhost");
        HttpUtil.setContentLength(request, 5);
        clientChannel.writeInbound(request);
        clientChannel.runPendingTasks();

        // HEADERS 帧不带 END_STREAM（有 body）
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(upstream.channel).write(captor.capture());
        assertThat(captor.getValue()).isInstanceOf(DefaultHttp2HeadersFrame.class);
        DefaultHttp2HeadersFrame headersFrame = (DefaultHttp2HeadersFrame) captor.getValue();
        assertThat(headersFrame.isEndStream()).isFalse();

        // 发送请求体
        DefaultLastHttpContent lastContent = new DefaultLastHttpContent(
                Unpooled.copiedBuffer("hello", CharsetUtil.UTF_8));
        clientChannel.writeInbound(lastContent);

        // 验证 DATA 帧通过 channel.write() 或 writeAndFlush() 写出
        // writeDataFrame 对 LastHttpContent 调用 h2Channel.writeAndFlush()
        // 但 writeDataFrame 内部先 write 再 flush（分开调用），需要看实际实现
        // 实际代码：h2Channel.write(dataFrame) + h2Channel.flush()
        ArgumentCaptor<Object> dataCaptor = ArgumentCaptor.forClass(Object.class);
        verify(upstream.channel, org.mockito.Mockito.atLeast(2)).write(dataCaptor.capture());
        List<Object> allWritten = dataCaptor.getAllValues();
        // 第二个写出的应该是 DATA 帧
        Object dataWritten = allWritten.get(1);
        assertThat(dataWritten).isInstanceOf(DefaultHttp2DataFrame.class);
        DefaultHttp2DataFrame dataFrame = (DefaultHttp2DataFrame) dataWritten;
        assertThat(dataFrame.isEndStream()).isTrue();

        clientChannel.finishAndReleaseAll();
    }

    @Test
    void noBodyRequestHeadersFrameHasEndStream() throws Exception {
        Route route = createRoute("svc", "/api", "http://localhost:8081");
        MockH2Upstream upstream = mockH2Upstream();
        when(connectionPool.acquire("localhost", 8081))
                .thenReturn(CompletableFuture.completedFuture(upstream.channel));
        ProxyHandler handler = createHandler(route);
        EmbeddedChannel clientChannel = new EmbeddedChannel(handler);

        DefaultHttpRequest request = new DefaultHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.GET, "/api/test");
        request.headers().set(HttpHeaderNames.HOST, "localhost");
        clientChannel.writeInbound(request);
        clientChannel.runPendingTasks();

        // GET 无 body，HEADERS 帧带 END_STREAM
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(upstream.channel).write(captor.capture());
        assertThat(captor.getValue()).isInstanceOf(DefaultHttp2HeadersFrame.class);
        DefaultHttp2HeadersFrame headersFrame = (DefaultHttp2HeadersFrame) captor.getValue();
        assertThat(headersFrame.isEndStream()).isTrue();

        clientChannel.finishAndReleaseAll();
    }

    // ========================================================================
    // H2 响应回调
    // Requirements: 5.3, 5.4
    // ========================================================================

    @Test
    void headRequest_noBodyResponse() throws Exception {
        Route route = createRoute("svc", "/api", "http://localhost:8081");
        MockH2Upstream upstream = mockH2Upstream();
        when(connectionPool.acquire("localhost", 8081))
                .thenReturn(CompletableFuture.completedFuture(upstream.channel));
        ProxyHandler handler = createHandler(route);
        EmbeddedChannel clientChannel = new EmbeddedChannel(handler);

        DefaultHttpRequest request = new DefaultHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.HEAD, "/api/test");
        request.headers().set(HttpHeaderNames.HOST, "localhost");
        clientChannel.writeInbound(request);
        clientChannel.runPendingTasks();

        // 模拟 H2 响应回调（HEAD 请求 → no body）
        handler.onH2Response(new DefaultHttpResponse(
                HttpVersion.HTTP_1_1, HttpResponseStatus.OK));
        clientChannel.runPendingTasks();

        FullHttpResponse response = clientChannel.readOutbound();
        assertThat(response).isNotNull();
        assertThat(response.status()).isEqualTo(HttpResponseStatus.OK);
        response.release();
        clientChannel.finishAndReleaseAll();
    }

    @Test
    void status204_noBodyResponse() throws Exception {
        Route route = createRoute("svc", "/api", "http://localhost:8081");
        MockH2Upstream upstream = mockH2Upstream();
        when(connectionPool.acquire("localhost", 8081))
                .thenReturn(CompletableFuture.completedFuture(upstream.channel));
        ProxyHandler handler = createHandler(route);
        EmbeddedChannel clientChannel = new EmbeddedChannel(handler);

        DefaultHttpRequest request = new DefaultHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.GET, "/api/test");
        request.headers().set(HttpHeaderNames.HOST, "localhost");
        clientChannel.writeInbound(request);
        clientChannel.runPendingTasks();

        handler.onH2Response(new DefaultHttpResponse(
                HttpVersion.HTTP_1_1, HttpResponseStatus.NO_CONTENT));
        clientChannel.runPendingTasks();

        FullHttpResponse response = clientChannel.readOutbound();
        assertThat(response).isNotNull();
        assertThat(response.status()).isEqualTo(HttpResponseStatus.NO_CONTENT);
        response.release();
        clientChannel.finishAndReleaseAll();
    }

    @Test
    void status304_noBodyResponse() throws Exception {
        Route route = createRoute("svc", "/api", "http://localhost:8081");
        MockH2Upstream upstream = mockH2Upstream();
        when(connectionPool.acquire("localhost", 8081))
                .thenReturn(CompletableFuture.completedFuture(upstream.channel));
        ProxyHandler handler = createHandler(route);
        EmbeddedChannel clientChannel = new EmbeddedChannel(handler);

        DefaultHttpRequest request = new DefaultHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.GET, "/api/test");
        request.headers().set(HttpHeaderNames.HOST, "localhost");
        clientChannel.writeInbound(request);
        clientChannel.runPendingTasks();

        handler.onH2Response(new DefaultHttpResponse(
                HttpVersion.HTTP_1_1, HttpResponseStatus.NOT_MODIFIED));
        clientChannel.runPendingTasks();

        FullHttpResponse response = clientChannel.readOutbound();
        assertThat(response).isNotNull();
        assertThat(response.status()).isEqualTo(HttpResponseStatus.NOT_MODIFIED);
        response.release();
        clientChannel.finishAndReleaseAll();
    }

    // ========================================================================
    // tracing 头注入
    // Requirements: 5.1
    // ========================================================================

    @Test
    void tracingEnabled_injectsTraceparentHeader() throws Exception {
        Route route = createRoute("svc", "/api", "http://localhost:8081");
        MockH2Upstream upstream = mockH2Upstream();
        when(connectionPool.acquire("localhost", 8081))
                .thenReturn(CompletableFuture.completedFuture(upstream.channel));

        ObservabilityProperties tracingConfig = new ObservabilityProperties();
        tracingConfig.setTracingEnabled(true);

        InFlightRequestTracker inFlightTracker = new InFlightRequestTracker();
        ProxyContext proxyCtx = new ProxyContext(limitConfig, connectionPool,
                metricsCollector, accessLogWriter, tracingConfig,
                inFlightTracker, null);
        ProxyHandler handler = new ProxyHandler(route, proxyCtx);
        EmbeddedChannel clientChannel = new EmbeddedChannel(handler);

        clientChannel.attr(TraceContextHandler.TRACEPARENT_KEY)
                .set("00-traceid-spanid-01");

        DefaultHttpRequest request = new DefaultHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.GET, "/api/test");
        request.headers().set(HttpHeaderNames.HOST, "localhost");
        clientChannel.writeInbound(request);
        clientChannel.runPendingTasks();

        // 验证 HEADERS 帧通过 channel.write() 写出，且包含 traceparent
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(upstream.channel).write(captor.capture());
        assertThat(captor.getValue()).isInstanceOf(DefaultHttp2HeadersFrame.class);
        DefaultHttp2HeadersFrame headersFrame = (DefaultHttp2HeadersFrame) captor.getValue();
        Http2Headers h2Headers = headersFrame.headers();
        assertThat(h2Headers.get("traceparent")).isNotNull();
        assertThat(h2Headers.get("traceparent").toString())
                .isEqualTo("00-traceid-spanid-01");

        clientChannel.finishAndReleaseAll();
    }

    // ========================================================================
    // 客户端断开时从映射表移除 streamId
    // Requirements: 6.4
    // ========================================================================

    @Test
    void clientDisconnectRemovesStreamFromDemux() throws Exception {
        Route route = createRoute("svc", "/api", "http://localhost:8081");
        MockH2Upstream upstream = mockH2Upstream();
        when(connectionPool.acquire("localhost", 8081))
                .thenReturn(CompletableFuture.completedFuture(upstream.channel));
        ProxyHandler handler = createHandler(route);
        EmbeddedChannel clientChannel = new EmbeddedChannel(handler);

        DefaultHttpRequest request = new DefaultHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.GET, "/api/test");
        request.headers().set(HttpHeaderNames.HOST, "localhost");
        clientChannel.writeInbound(request);
        clientChannel.runPendingTasks();

        // 客户端断开
        clientChannel.close();

        // 验证从映射表移除
        verify(upstream.demux).remove(1);

        clientChannel.finishAndReleaseAll();
    }

    @Test
    void clientDisconnectsDuringAcquire_shouldNotSendResponse() throws Exception {
        Route route = createRoute("svc", "/api", "http://localhost:8081");
        CompletableFuture<Channel> acquireFuture = new CompletableFuture<>();
        when(connectionPool.acquire("localhost", 8081)).thenReturn(acquireFuture);
        ProxyHandler handler = createHandler(route);
        EmbeddedChannel clientChannel = new EmbeddedChannel(handler);

        DefaultHttpRequest request = new DefaultHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.GET, "/api/test");
        request.headers().set(HttpHeaderNames.HOST, "localhost");
        clientChannel.writeInbound(request);

        clientChannel.close();

        MockH2Upstream upstream = mockH2Upstream();
        acquireFuture.complete(upstream.channel);
        clientChannel.runPendingTasks();

        assertThat((Object) clientChannel.readOutbound()).isNull();
        clientChannel.finishAndReleaseAll();
    }

    // ========================================================================
    // 缓冲 content 在连接就绪前
    // ========================================================================

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

    // ========================================================================
    // 其他错误场景
    // ========================================================================

    @Test
    void invalidUpstreamUri_returns502() throws Exception {
        Route route = createRoute("svc", "/api", "://invalid-uri");
        ProxyHandler handler = createHandler(route);
        EmbeddedChannel channel = new EmbeddedChannel(handler);

        DefaultHttpRequest request = new DefaultHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.GET, "/api/test");
        request.headers().set(HttpHeaderNames.HOST, "localhost");
        channel.writeInbound(request);

        FullHttpResponse response = channel.readOutbound();
        assertThat(response).isNotNull();
        assertThat(response.status()).isEqualTo(HttpResponseStatus.BAD_GATEWAY);
        response.release();
        channel.finish();
    }

    @Test
    void connectionPoolExhausted_returns503() throws Exception {
        Route route = createRoute("svc", "/api", "http://localhost:8081");
        when(connectionPool.acquire("localhost", 8081))
                .thenReturn(CompletableFuture.failedFuture(
                        new IllegalStateException("pool exhausted")));
        ProxyHandler handler = createHandler(route);
        EmbeddedChannel channel = new EmbeddedChannel(handler);

        DefaultHttpRequest request = new DefaultHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.GET, "/api/test");
        request.headers().set(HttpHeaderNames.HOST, "localhost");
        channel.writeInbound(request);
        channel.runPendingTasks();

        FullHttpResponse response = channel.readOutbound();
        assertThat(response).isNotNull();
        assertThat(response.status()).isEqualTo(HttpResponseStatus.SERVICE_UNAVAILABLE);
        response.release();
        channel.finish();
    }

    @Test
    void accumulatedBodyExceedsLimit_returns413() throws Exception {
        Route route = createRoute("svc", "/api", "http://localhost:8081");
        MockH2Upstream upstream = mockH2Upstream();
        when(connectionPool.acquire("localhost", 8081))
                .thenReturn(CompletableFuture.completedFuture(upstream.channel));
        ProxyHandler handler = createHandler(route);
        EmbeddedChannel clientChannel = new EmbeddedChannel(handler);

        DefaultHttpRequest request = new DefaultHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.POST, "/api/test");
        request.headers().set(HttpHeaderNames.HOST, "localhost");
        clientChannel.writeInbound(request);
        clientChannel.runPendingTasks();

        byte[] bigBody = new byte[1025];
        DefaultHttpContent content = new DefaultHttpContent(
                Unpooled.wrappedBuffer(bigBody));
        clientChannel.writeInbound(content);

        FullHttpResponse response = clientChannel.readOutbound();
        assertThat(response).isNotNull();
        assertThat(response.status())
                .isEqualTo(HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE);
        response.release();
        clientChannel.finishAndReleaseAll();
    }

    @Test
    void idleStateEvent_returns504() throws Exception {
        Route route = createRoute("svc", "/api", "http://localhost:8081");
        ProxyHandler handler = createHandler(route);
        EmbeddedChannel channel = new EmbeddedChannel(handler);

        channel.pipeline().fireUserEventTriggered(
                IdleStateEvent.READER_IDLE_STATE_EVENT);

        FullHttpResponse response = channel.readOutbound();
        assertThat(response).isNotNull();
        assertThat(response.status()).isEqualTo(HttpResponseStatus.GATEWAY_TIMEOUT);
        response.release();
        channel.finish();
    }

    @Test
    void exceptionCaught_returns500() throws Exception {
        Route route = createRoute("svc", "/api", "http://localhost:8081");
        ProxyHandler handler = createHandler(route);
        EmbeddedChannel channel = new EmbeddedChannel(handler);

        channel.pipeline().fireExceptionCaught(new RuntimeException("test error"));

        FullHttpResponse response = channel.readOutbound();
        assertThat(response).isNotNull();
        assertThat(response.status())
                .isEqualTo(HttpResponseStatus.INTERNAL_SERVER_ERROR);
        response.release();
        channel.finish();
    }

    // ========================================================================
    // onH2Error 回调
    // Requirements: 6.2, 6.3
    // ========================================================================

    @Test
    void onH2Error_returns502() throws Exception {
        Route route = createRoute("svc", "/api", "http://localhost:8081");
        MockH2Upstream upstream = mockH2Upstream();
        when(connectionPool.acquire("localhost", 8081))
                .thenReturn(CompletableFuture.completedFuture(upstream.channel));
        ProxyHandler handler = createHandler(route);
        EmbeddedChannel clientChannel = new EmbeddedChannel(handler);

        DefaultHttpRequest request = new DefaultHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.GET, "/api/test");
        request.headers().set(HttpHeaderNames.HOST, "localhost");
        clientChannel.writeInbound(request);
        clientChannel.runPendingTasks();

        handler.onH2Error(new RuntimeException("stream reset"));

        FullHttpResponse response = clientChannel.readOutbound();
        assertThat(response).isNotNull();
        assertThat(response.status()).isEqualTo(HttpResponseStatus.BAD_GATEWAY);
        response.release();
        clientChannel.finishAndReleaseAll();
    }
}
