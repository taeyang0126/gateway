package com.lei.gateway.proxy;

import static org.assertj.core.api.Assertions.assertThat;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.CharsetUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DrainHandlerTest {

    private DrainHandler handler;
    private EmbeddedChannel channel;

    @BeforeEach
    void setUp() {
        handler = new DrainHandler();
        channel = new EmbeddedChannel(handler);
    }

    @Test
    void initialStateIsNotDraining() {
        assertThat(handler.isDraining()).isFalse();
    }

    @Test
    void activateDrainSetsState() {
        handler.activateDrain();
        assertThat(handler.isDraining()).isTrue();
    }

    @Test
    void nonDrainingPassesThroughHttpRequest() {
        DefaultFullHttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.GET, "/test");

        channel.writeInbound(request);

        Object forwarded = channel.readInbound();
        assertThat(forwarded).isSameAs(request);
        request.release();
    }

    @Test
    void drainingReturns503ForHttpRequest() {
        handler.activateDrain();

        DefaultFullHttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.GET, "/test");
        channel.writeInbound(request);

        FullHttpResponse response = channel.readOutbound();
        assertThat(response).isNotNull();
        assertThat(response.status()).isEqualTo(HttpResponseStatus.SERVICE_UNAVAILABLE);
        assertThat(response.headers().get(HttpHeaderNames.CONNECTION))
                .isEqualTo(HttpHeaderValues.CLOSE.toString());
        assertThat(response.headers().get(HttpHeaderNames.CONTENT_TYPE))
                .isEqualTo("application/json");

        String body = response.content().toString(CharsetUtil.UTF_8);
        assertThat(body).contains("Server is shutting down");
        response.release();
    }

    @Test
    void drainingPassesThroughHealthLive() {
        handler.activateDrain();

        DefaultFullHttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.GET, "/health/live");
        channel.writeInbound(request);

        Object forwarded = channel.readInbound();
        assertThat(forwarded).isSameAs(request);
        assertThat((FullHttpResponse) channel.readOutbound()).isNull();
        request.release();
    }

    @Test
    void drainingPassesThroughHealthReady() {
        handler.activateDrain();

        DefaultFullHttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.GET, "/health/ready");
        channel.writeInbound(request);

        Object forwarded = channel.readInbound();
        assertThat(forwarded).isSameAs(request);
        assertThat((FullHttpResponse) channel.readOutbound()).isNull();
        request.release();
    }

    @Test
    void drainingPassesThroughHealth() {
        handler.activateDrain();

        DefaultFullHttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.GET, "/health");
        channel.writeInbound(request);

        Object forwarded = channel.readInbound();
        assertThat(forwarded).isSameAs(request);
        assertThat((FullHttpResponse) channel.readOutbound()).isNull();
        request.release();
    }

    @Test
    void drainingReleasesNonHttpRequestMessage() {
        handler.activateDrain();

        ByteBuf buf = Unpooled.copiedBuffer("not http", CharsetUtil.UTF_8);
        assertThat(buf.refCnt()).isEqualTo(1);

        channel.writeInbound(buf);

        // 非 HttpRequest 消息应被释放，不透传
        assertThat((Object) channel.readInbound()).isNull();
        assertThat(buf.refCnt()).isZero();
    }

    @Test
    void nonDrainingPassesThroughNonHttpRequestMessage() {
        ByteBuf buf = Unpooled.copiedBuffer("not http", CharsetUtil.UTF_8);

        channel.writeInbound(buf);

        Object forwarded = channel.readInbound();
        assertThat(forwarded).isSameAs(buf);
        buf.release();
    }

    @Test
    void exceptionCaughtClosesChannel() {
        channel.pipeline().fireExceptionCaught(new RuntimeException("test"));
        assertThat(channel.isOpen()).isFalse();
    }
}
