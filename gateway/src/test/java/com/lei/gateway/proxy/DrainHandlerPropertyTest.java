package com.lei.gateway.proxy;

import static org.assertj.core.api.Assertions.assertThat;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.CharsetUtil;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Arbitraries;

class DrainHandlerPropertyTest {

    @Provide
    Arbitrary<HttpMethod> httpMethods() {
        return Arbitraries.of(
                HttpMethod.GET, HttpMethod.POST, HttpMethod.PUT,
                HttpMethod.DELETE, HttpMethod.PATCH, HttpMethod.HEAD,
                HttpMethod.OPTIONS);
    }

    @Provide
    Arbitrary<String> uris() {
        return Arbitraries.strings()
                .alpha().ofMinLength(1).ofMaxLength(50)
                .map(s -> "/" + s)
                .filter(u -> !u.equals("/health")
                        && !u.equals("/health/live")
                        && !u.equals("/health/ready"));
    }

    // Feature: graceful-shutdown, Property 4: 排空状态下拒绝所有请求
    @Property(tries = 100)
    void drainingRejectsAllRequests(
            @ForAll("httpMethods") HttpMethod method,
            @ForAll("uris") String uri) {

        DrainHandler handler = new DrainHandler();
        handler.activateDrain();
        EmbeddedChannel channel = new EmbeddedChannel(handler);

        DefaultFullHttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, method, uri);
        channel.writeInbound(request);

        FullHttpResponse response = channel.readOutbound();
        assertThat(response).isNotNull();
        assertThat(response.status())
                .isEqualTo(HttpResponseStatus.SERVICE_UNAVAILABLE);
        assertThat(response.headers().get(HttpHeaderNames.CONNECTION))
                .isEqualTo(HttpHeaderValues.CLOSE.toString());

        String body = response.content().toString(CharsetUtil.UTF_8);
        assertThat(body).contains("Server is shutting down");
        response.release();
        channel.finishAndReleaseAll();
    }

    // Feature: graceful-shutdown, Property 5: 非排空状态下透传所有请求
    @Property(tries = 100)
    void nonDrainingPassesThroughAllRequests(
            @ForAll("httpMethods") HttpMethod method,
            @ForAll("uris") String uri) {

        DrainHandler handler = new DrainHandler();
        EmbeddedChannel channel = new EmbeddedChannel(handler);

        DefaultFullHttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, method, uri);
        channel.writeInbound(request);

        // 非排空状态下请求应被透传
        Object forwarded = channel.readInbound();
        assertThat(forwarded).isSameAs(request);

        // 不应产生任何响应
        assertThat((Object) channel.readOutbound()).isNull();

        request.release();
        channel.finishAndReleaseAll();
    }
}
