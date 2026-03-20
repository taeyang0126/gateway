package com.example.gateway.core.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.gateway.core.config.ObservabilityProperties;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpVersion;
import org.junit.jupiter.api.Test;

/**
 * TraceContextHandler 单元测试。
 */
class TraceContextHandlerTest {

    private static final String TRACEPARENT_REGEX =
            "[0-9a-f]{2}-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}";

    private EmbeddedChannel createChannel(ObservabilityProperties config) {
        return new EmbeddedChannel(new TraceContextHandler(config));
    }

    private ObservabilityProperties enabledConfig() {
        ObservabilityProperties config = new ObservabilityProperties();
        config.setTracingEnabled(true);
        return config;
    }

    @Test
    void parsesValidTraceparent() {
        EmbeddedChannel channel = createChannel(enabledConfig());
        HttpRequest request = new DefaultHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.GET, "/api/test");
        request.headers().set("traceparent",
                "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");
        request.headers().set("tracestate", "vendor=value");

        channel.writeInbound(request);

        String traceparent = channel.attr(TraceContextHandler.TRACEPARENT_KEY).get();
        String traceId = channel.attr(TraceContextHandler.TRACE_ID_KEY).get();
        String tracestate = channel.attr(TraceContextHandler.TRACESTATE_KEY).get();

        // trace-id 保持不变
        assertThat(traceId).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
        // traceparent 保留原始 trace-id，但 span-id 是新生成的
        assertThat(traceparent).startsWith("00-4bf92f3577b34da6a3ce929d0e0e4736-");
        assertThat(traceparent).endsWith("-01");
        // span-id 不同于原始值
        assertThat(traceparent).doesNotContain("00f067aa0ba902b7");
        assertThat(traceparent).matches(TRACEPARENT_REGEX);
        // tracestate 原样传递
        assertThat(tracestate).isEqualTo("vendor=value");

        // 消息被传递
        assertThat((Object) channel.readInbound()).isSameAs(request);
    }

    @Test
    void generatesNewTraceparentWhenMissing() {
        EmbeddedChannel channel = createChannel(enabledConfig());
        HttpRequest request = new DefaultHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.GET, "/api/test");

        channel.writeInbound(request);

        String traceparent = channel.attr(TraceContextHandler.TRACEPARENT_KEY).get();
        String traceId = channel.attr(TraceContextHandler.TRACE_ID_KEY).get();
        String tracestate = channel.attr(TraceContextHandler.TRACESTATE_KEY).get();

        assertThat(traceparent).matches(TRACEPARENT_REGEX);
        assertThat(traceparent).startsWith("00-");
        assertThat(traceparent).endsWith("-01");
        assertThat(traceId).hasSize(32);
        assertThat(tracestate).isNull();

        assertThat((Object) channel.readInbound()).isSameAs(request);
    }

    @Test
    void invalidTraceparentGeneratesNew() {
        EmbeddedChannel channel = createChannel(enabledConfig());
        HttpRequest request = new DefaultHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.GET, "/api/test");
        request.headers().set("traceparent", "invalid-format");
        request.headers().set("tracestate", "vendor=value");

        channel.writeInbound(request);

        String traceparent = channel.attr(TraceContextHandler.TRACEPARENT_KEY).get();
        String traceId = channel.attr(TraceContextHandler.TRACE_ID_KEY).get();
        String tracestate = channel.attr(TraceContextHandler.TRACESTATE_KEY).get();

        assertThat(traceparent).matches(TRACEPARENT_REGEX);
        assertThat(traceId).hasSize(32);
        // 非法格式时 tracestate 被丢弃
        assertThat(tracestate).isNull();

        assertThat((Object) channel.readInbound()).isSameAs(request);
    }

    @Test
    void disabledTracingPassesThroughWithoutAttributes() {
        ObservabilityProperties config = new ObservabilityProperties();
        config.setTracingEnabled(false);
        EmbeddedChannel channel = createChannel(config);
        HttpRequest request = new DefaultHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.GET, "/api/test");

        channel.writeInbound(request);

        assertThat(channel.attr(TraceContextHandler.TRACEPARENT_KEY).get()).isNull();
        assertThat(channel.attr(TraceContextHandler.TRACE_ID_KEY).get()).isNull();
        assertThat((Object) channel.readInbound()).isSameAs(request);
    }
}
