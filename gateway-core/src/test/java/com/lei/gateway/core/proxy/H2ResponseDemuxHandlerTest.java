package com.lei.gateway.core.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.DefaultHttp2SettingsFrame;
import io.netty.handler.codec.http2.Http2DataFrame;
import io.netty.handler.codec.http2.Http2FrameStream;
import io.netty.handler.codec.http2.Http2GoAwayFrame;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2HeadersFrame;
import io.netty.handler.codec.http2.Http2ResetFrame;
import io.netty.handler.codec.http2.Http2Settings;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * H2ResponseDemuxHandler 单元测试。
 *
 * <p>验证 SETTINGS、GOAWAY、RST_STREAM、HEADERS、DATA 帧处理，
 * 以及 canCreateStream() 阈值判断和 channelInactive 清理逻辑。
 */
class H2ResponseDemuxHandlerTest {

    // ========================================================================
    // 辅助方法
    // ========================================================================

    private UpstreamConnectionPool mockPool() {
        return mock(UpstreamConnectionPool.class);
    }

    private record HandlerWithChannel(H2ResponseDemuxHandler handler, EmbeddedChannel channel) {}

    private HandlerWithChannel createHandler() {
        return createHandler(mockPool());
    }

    private HandlerWithChannel createHandler(UpstreamConnectionPool pool) {
        H2ResponseDemuxHandler handler = new H2ResponseDemuxHandler(pool);
        EmbeddedChannel channel = new EmbeddedChannel(handler);
        return new HandlerWithChannel(handler, channel);
    }

    private ChannelHandlerContext handlerCtx(HandlerWithChannel hc) {
        return hc.channel.pipeline().context(hc.handler);
    }

    private Http2FrameStream mockStream(int streamId) {
        Http2FrameStream stream = mock(Http2FrameStream.class);
        when(stream.id()).thenReturn(streamId);
        return stream;
    }

    private Http2HeadersFrame mockHeadersFrame(int streamId, boolean endStream) {
        Http2Headers headers = new DefaultHttp2Headers().status("200");
        return mockHeadersFrame(streamId, headers, endStream);
    }

    private Http2HeadersFrame mockHeadersFrame(int streamId, Http2Headers headers, boolean endStream) {
        Http2FrameStream stream = mockStream(streamId);
        Http2HeadersFrame frame = mock(Http2HeadersFrame.class);
        when(frame.stream()).thenReturn(stream);
        when(frame.headers()).thenReturn(headers);
        when(frame.isEndStream()).thenReturn(endStream);
        return frame;
    }

    private Http2DataFrame mockDataFrame(int streamId, ByteBuf content, boolean endStream) {
        Http2FrameStream stream = mockStream(streamId);
        Http2DataFrame frame = mock(Http2DataFrame.class);
        when(frame.stream()).thenReturn(stream);
        when(frame.content()).thenReturn(content);
        when(frame.isEndStream()).thenReturn(endStream);
        when(frame.release()).thenReturn(false);
        return frame;
    }

    private Http2ResetFrame mockResetFrame(int streamId, long errorCode) {
        Http2FrameStream stream = mockStream(streamId);
        Http2ResetFrame frame = mock(Http2ResetFrame.class);
        when(frame.stream()).thenReturn(stream);
        when(frame.errorCode()).thenReturn(errorCode);
        return frame;
    }

    private Http2GoAwayFrame mockGoAwayFrame(int lastStreamId, long errorCode) {
        Http2GoAwayFrame frame = mock(Http2GoAwayFrame.class);
        when(frame.lastStreamId()).thenReturn(lastStreamId);
        when(frame.errorCode()).thenReturn(errorCode);
        when(frame.content()).thenReturn(Unpooled.EMPTY_BUFFER);
        return frame;
    }

    // ========================================================================
    // SETTINGS 帧更新 maxConcurrentStreams
    // Requirements: 4.1
    // ========================================================================

    @Test
    void settingsFrameUpdatesMaxConcurrentStreams() {
        var hc = createHandler();

        // 默认无限制
        assertThat(hc.handler.canCreateStream()).isTrue();

        Http2Settings settings = new Http2Settings().maxConcurrentStreams(10);
        hc.channel.writeInbound(new DefaultHttp2SettingsFrame(settings));

        // 注册 10 个 stream 后应不可再创建
        for (int ii = 0; ii < 10; ii++) {
            assertThat(hc.handler.canCreateStream()).isTrue();
            hc.handler.incrementActiveStream();
            hc.handler.register(ii * 2 + 1, mock(ProxyHandler.class));
        }
        assertThat(hc.handler.canCreateStream()).isFalse();

        hc.channel.finishAndReleaseAll();
    }

    @Test
    void settingsFrameWithoutMaxConcurrentStreamsKeepsDefault() {
        var hc = createHandler();

        // 发送不含 maxConcurrentStreams 的 SETTINGS 帧
        Http2Settings settings = new Http2Settings();
        hc.channel.writeInbound(new DefaultHttp2SettingsFrame(settings));

        // 默认 Integer.MAX_VALUE，canCreateStream 应仍为 true
        hc.handler.incrementActiveStream();
        hc.handler.register(1, mock(ProxyHandler.class));
        assertThat(hc.handler.canCreateStream()).isTrue();

        hc.channel.finishAndReleaseAll();
    }

    @Test
    void settingsFrameCanUpdateMultipleTimes() {
        var hc = createHandler();

        // 先设为 2
        hc.channel.writeInbound(new DefaultHttp2SettingsFrame(new Http2Settings().maxConcurrentStreams(2)));
        hc.handler.incrementActiveStream();
        hc.handler.register(1, mock(ProxyHandler.class));
        hc.handler.incrementActiveStream();
        hc.handler.register(3, mock(ProxyHandler.class));
        assertThat(hc.handler.canCreateStream()).isFalse();

        // 上调为 5
        hc.channel.writeInbound(new DefaultHttp2SettingsFrame(new Http2Settings().maxConcurrentStreams(5)));
        assertThat(hc.handler.canCreateStream()).isTrue();

        hc.channel.finishAndReleaseAll();
    }

    // ========================================================================
    // canCreateStream() 阈值判断
    // Requirements: 4.1, 4.2
    // ========================================================================

    @Test
    void canCreateStreamReturnsFalseWhenAtLimit() {
        var hc = createHandler();
        hc.channel.writeInbound(new DefaultHttp2SettingsFrame(new Http2Settings().maxConcurrentStreams(1)));

        assertThat(hc.handler.canCreateStream()).isTrue();
        hc.handler.incrementActiveStream();
        hc.handler.register(1, mock(ProxyHandler.class));
        assertThat(hc.handler.canCreateStream()).isFalse();

        // 移除后恢复
        hc.handler.remove(1);
        assertThat(hc.handler.canCreateStream()).isTrue();

        hc.channel.finishAndReleaseAll();
    }


    // ========================================================================
    // HEADERS 帧处理
    // Requirements: 3.5
    // ========================================================================

    @Test
    void headersFrameRoutesToRegisteredHandler() {
        var hc = createHandler();
        ProxyHandler ph = mock(ProxyHandler.class);
        hc.handler.incrementActiveStream();
        hc.handler.register(1, ph);

        Http2Headers headers = new DefaultHttp2Headers().status("200").add("x-custom", "val");
        hc.handler.channelRead(handlerCtx(hc), mockHeadersFrame(1, headers, false));

        ArgumentCaptor<HttpResponse> captor = ArgumentCaptor.forClass(HttpResponse.class);
        verify(ph).onH2Response(captor.capture());
        HttpResponse resp = captor.getValue();
        assertThat(resp.status().code()).isEqualTo(200);
        assertThat(resp.headers().get("x-custom")).isEqualTo("val");
        // 非 END_STREAM，不应调用 onH2Content 也不应 remove
        verify(ph, never()).onH2Content(any());
        assertThat(hc.handler.activeStreamCount()).isEqualTo(1);

        hc.channel.finishAndReleaseAll();
    }

    @Test
    void headersFrameWithEndStreamSendsLastContentAndRemoves() {
        var hc = createHandler();
        ProxyHandler ph = mock(ProxyHandler.class);
        hc.handler.incrementActiveStream();
        hc.handler.register(1, ph);

        hc.handler.channelRead(handlerCtx(hc), mockHeadersFrame(1, true));

        verify(ph).onH2Response(any(HttpResponse.class));
        ArgumentCaptor<HttpContent> captor = ArgumentCaptor.forClass(HttpContent.class);
        verify(ph).onH2Content(captor.capture());
        assertThat(captor.getValue()).isInstanceOf(LastHttpContent.class);
        assertThat(hc.handler.activeStreamCount()).isZero();

        hc.channel.finishAndReleaseAll();
    }

    @Test
    void headersFrameForUnknownStreamIdIsIgnored() {
        var hc = createHandler();
        // 不注册任何 handler，直接发 HEADERS 帧
        hc.handler.channelRead(handlerCtx(hc), mockHeadersFrame(99, false));
        // 不应抛异常，activeStreamCount 保持 0
        assertThat(hc.handler.activeStreamCount()).isZero();

        hc.channel.finishAndReleaseAll();
    }

    // ========================================================================
    // DATA 帧处理
    // Requirements: 3.5, 3.6
    // ========================================================================

    @Test
    void dataFrameRoutesToRegisteredHandler() {
        var hc = createHandler();
        ProxyHandler ph = mock(ProxyHandler.class);
        hc.handler.incrementActiveStream();
        hc.handler.register(1, ph);

        ByteBuf content = Unpooled.copiedBuffer("hello", StandardCharsets.UTF_8);
        hc.handler.channelRead(handlerCtx(hc), mockDataFrame(1, content, false));

        ArgumentCaptor<HttpContent> captor = ArgumentCaptor.forClass(HttpContent.class);
        verify(ph).onH2Content(captor.capture());
        HttpContent received = captor.getValue();
        assertThat(received).isNotInstanceOf(LastHttpContent.class);
        assertThat(received.content().toString(StandardCharsets.UTF_8)).isEqualTo("hello");
        assertThat(hc.handler.activeStreamCount()).isEqualTo(1);

        received.release();
        hc.channel.finishAndReleaseAll();
    }

    @Test
    void dataFrameWithEndStreamSendsLastContentAndRemoves() {
        var hc = createHandler();
        ProxyHandler ph = mock(ProxyHandler.class);
        hc.handler.incrementActiveStream();
        hc.handler.register(1, ph);

        ByteBuf content = Unpooled.copiedBuffer("done", StandardCharsets.UTF_8);
        hc.handler.channelRead(handlerCtx(hc), mockDataFrame(1, content, true));

        ArgumentCaptor<HttpContent> captor = ArgumentCaptor.forClass(HttpContent.class);
        verify(ph).onH2Content(captor.capture());
        assertThat(captor.getValue()).isInstanceOf(LastHttpContent.class);
        assertThat(hc.handler.activeStreamCount()).isZero();

        captor.getValue().release();
        hc.channel.finishAndReleaseAll();
    }

    @Test
    void dataFrameForUnknownStreamIdIsReleasedAndIgnored() {
        var hc = createHandler();
        ByteBuf content = Unpooled.copiedBuffer("orphan", StandardCharsets.UTF_8);
        Http2DataFrame frame = mockDataFrame(99, content, false);

        hc.handler.channelRead(handlerCtx(hc), frame);

        // 应调用 release 释放 ByteBuf
        verify(frame).release();
        assertThat(hc.handler.activeStreamCount()).isZero();

        hc.channel.finishAndReleaseAll();
    }


    // ========================================================================
    // RST_STREAM 处理
    // Requirements: 6.3
    // ========================================================================

    @Test
    void resetFrameTriggersOnH2ErrorAndRemovesStream() {
        var hc = createHandler();
        ProxyHandler ph = mock(ProxyHandler.class);
        hc.handler.incrementActiveStream();
        hc.handler.register(1, ph);

        hc.handler.channelRead(handlerCtx(hc), mockResetFrame(1, 8));

        ArgumentCaptor<Throwable> captor = ArgumentCaptor.forClass(Throwable.class);
        verify(ph).onH2Error(captor.capture());
        assertThat(captor.getValue()).isInstanceOf(H2ResponseDemuxHandler.H2StreamResetException.class);
        H2ResponseDemuxHandler.H2StreamResetException ex =
                (H2ResponseDemuxHandler.H2StreamResetException) captor.getValue();
        assertThat(ex.getStreamId()).isEqualTo(1);
        assertThat(ex.getErrorCode()).isEqualTo(8);
        assertThat(hc.handler.activeStreamCount()).isZero();

        hc.channel.finishAndReleaseAll();
    }

    @Test
    void resetFrameForUnknownStreamIdIsIgnored() {
        var hc = createHandler();
        // 不注册任何 handler
        hc.handler.channelRead(handlerCtx(hc), mockResetFrame(99, 0));
        assertThat(hc.handler.activeStreamCount()).isZero();

        hc.channel.finishAndReleaseAll();
    }

    @Test
    void resetFrameOnlyAffectsTargetStream() {
        var hc = createHandler();
        ProxyHandler ph1 = mock(ProxyHandler.class);
        ProxyHandler ph3 = mock(ProxyHandler.class);
        hc.handler.incrementActiveStream();
        hc.handler.register(1, ph1);
        hc.handler.incrementActiveStream();
        hc.handler.register(3, ph3);

        hc.handler.channelRead(handlerCtx(hc), mockResetFrame(1, 2));

        verify(ph1).onH2Error(any(H2ResponseDemuxHandler.H2StreamResetException.class));
        verify(ph3, never()).onH2Error(any());
        assertThat(hc.handler.activeStreamCount()).isEqualTo(1);

        hc.channel.finishAndReleaseAll();
    }

    // ========================================================================
    // GOAWAY 处理
    // Requirements: 6.1
    // ========================================================================

    @Test
    void goAwayRetiresConnectionAndPartitionsStreams() {
        UpstreamConnectionPool pool = mockPool();
        var hc = createHandler(pool);

        ProxyHandler ph1 = mock(ProxyHandler.class);
        ProxyHandler ph3 = mock(ProxyHandler.class);
        ProxyHandler ph5 = mock(ProxyHandler.class);
        hc.handler.incrementActiveStream();
        hc.handler.register(1, ph1);
        hc.handler.incrementActiveStream();
        hc.handler.register(3, ph3);
        hc.handler.incrementActiveStream();
        hc.handler.register(5, ph5);

        // GOAWAY lastStreamId=3: stream 1,3 存活，stream 5 被驱逐
        hc.handler.channelRead(handlerCtx(hc), mockGoAwayFrame(3, 0));

        verify(pool).retire(hc.channel);
        verify(ph1, never()).onH2Error(any());
        verify(ph3, never()).onH2Error(any());
        verify(ph5).onH2Error(any(H2ResponseDemuxHandler.H2GoAwayException.class));

        // stream 1,3 仍在映射表中
        assertThat(hc.handler.activeStreamCount()).isEqualTo(2);
        assertThat(hc.handler.remove(1)).isSameAs(ph1);
        assertThat(hc.handler.remove(3)).isSameAs(ph3);
        // stream 5 已被移除
        assertThat(hc.handler.remove(5)).isNull();

        hc.channel.finishAndReleaseAll();
    }

    @Test
    void goAwayClosesChannelWhenNoActiveStreams() {
        UpstreamConnectionPool pool = mockPool();
        var hc = createHandler(pool);

        ProxyHandler ph1 = mock(ProxyHandler.class);
        hc.handler.incrementActiveStream();
        hc.handler.register(1, ph1);

        // GOAWAY lastStreamId=0: 所有 stream 被驱逐
        hc.handler.channelRead(handlerCtx(hc), mockGoAwayFrame(0, 0));

        verify(pool).retire(hc.channel);
        verify(ph1).onH2Error(any(H2ResponseDemuxHandler.H2GoAwayException.class));
        assertThat(hc.handler.activeStreamCount()).isZero();
        // 映射表空后应关闭连接
        assertThat(hc.channel.isOpen()).isFalse();
    }

    @Test
    void goAwaySurvivingStreamsCanStillReceiveResponses() {
        UpstreamConnectionPool pool = mockPool();
        var hc = createHandler(pool);

        ProxyHandler ph1 = mock(ProxyHandler.class);
        ProxyHandler ph3 = mock(ProxyHandler.class);
        hc.handler.incrementActiveStream();
        hc.handler.register(1, ph1);
        hc.handler.incrementActiveStream();
        hc.handler.register(3, ph3);

        // GOAWAY lastStreamId=3: 两个 stream 都存活
        hc.handler.channelRead(handlerCtx(hc), mockGoAwayFrame(3, 0));

        // 存量 stream 仍可接收响应
        hc.handler.channelRead(handlerCtx(hc), mockHeadersFrame(1, true));
        verify(ph1).onH2Response(any(HttpResponse.class));
        assertThat(hc.handler.activeStreamCount()).isEqualTo(1);

        // 最后一个 stream 完成后关闭连接
        hc.handler.channelRead(handlerCtx(hc), mockHeadersFrame(3, true));
        verify(ph3).onH2Response(any(HttpResponse.class));
        assertThat(hc.handler.activeStreamCount()).isZero();
        assertThat(hc.channel.isOpen()).isFalse();
    }

    @Test
    void goAwayOnEmptyMapClosesImmediately() {
        UpstreamConnectionPool pool = mockPool();
        var hc = createHandler(pool);

        hc.handler.channelRead(handlerCtx(hc), mockGoAwayFrame(0, 0));

        verify(pool).retire(hc.channel);
        assertThat(hc.channel.isOpen()).isFalse();
    }

    @Test
    void goAwayExceptionCarriesLastStreamIdAndErrorCode() {
        UpstreamConnectionPool pool = mockPool();
        var hc = createHandler(pool);

        ProxyHandler ph5 = mock(ProxyHandler.class);
        hc.handler.incrementActiveStream();
        hc.handler.register(5, ph5);

        hc.handler.channelRead(handlerCtx(hc), mockGoAwayFrame(3, 11));

        ArgumentCaptor<Throwable> captor = ArgumentCaptor.forClass(Throwable.class);
        verify(ph5).onH2Error(captor.capture());
        H2ResponseDemuxHandler.H2GoAwayException ex =
                (H2ResponseDemuxHandler.H2GoAwayException) captor.getValue();
        assertThat(ex.getGoAwayLastStreamId()).isEqualTo(3);
        assertThat(ex.getErrorCode()).isEqualTo(11);

        hc.channel.finishAndReleaseAll();
    }


    // ========================================================================
    // channelInactive 清理
    // Requirements: 6.2
    // ========================================================================

    @Test
    void channelInactiveNotifiesAllHandlersAndClearsMap() {
        var hc = createHandler();
        ProxyHandler ph1 = mock(ProxyHandler.class);
        ProxyHandler ph3 = mock(ProxyHandler.class);
        ProxyHandler ph5 = mock(ProxyHandler.class);
        hc.handler.incrementActiveStream();
        hc.handler.register(1, ph1);
        hc.handler.incrementActiveStream();
        hc.handler.register(3, ph3);
        hc.handler.incrementActiveStream();
        hc.handler.register(5, ph5);

        hc.channel.close();

        verify(ph1).onH2Error(any(H2ResponseDemuxHandler.H2ChannelClosedException.class));
        verify(ph3).onH2Error(any(H2ResponseDemuxHandler.H2ChannelClosedException.class));
        verify(ph5).onH2Error(any(H2ResponseDemuxHandler.H2ChannelClosedException.class));
        assertThat(hc.handler.activeStreamCount()).isZero();
        assertThat(hc.handler.hasActiveStreams()).isFalse();
    }

    @Test
    void channelInactiveOnEmptyMapDoesNotThrow() {
        var hc = createHandler();
        assertThat(hc.handler.activeStreamCount()).isZero();
        hc.channel.close();
        assertThat(hc.handler.activeStreamCount()).isZero();
    }

    @Test
    void channelInactiveAfterRemoveDoesNotDoubleNotify() {
        var hc = createHandler();
        ProxyHandler ph = mock(ProxyHandler.class);
        hc.handler.incrementActiveStream();
        hc.handler.register(1, ph);

        // 先手动 remove
        hc.handler.remove(1);
        assertThat(hc.handler.activeStreamCount()).isZero();

        // 再触发 channelInactive
        hc.channel.close();

        // onH2Error 不应被调用（已经 remove 了）
        verify(ph, never()).onH2Error(any());
    }

    // ========================================================================
    // 未知帧类型透传
    // ========================================================================

    @Test
    void unknownMessageTypeIsPassedThrough() {
        var hc = createHandler();
        String unknownMsg = "not-an-h2-frame";
        hc.channel.writeInbound(unknownMsg);

        // 应透传到下一个 handler，EmbeddedChannel 会缓存
        assertThat(hc.channel.<String>readInbound()).isEqualTo(unknownMsg);

        hc.channel.finishAndReleaseAll();
    }
}
