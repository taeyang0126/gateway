package com.lei.gateway.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import com.lei.gateway.config.Route;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * PluginContext 构造和字段访问单元测试。
 */
class PluginContextTest {

    private ChannelHandlerContext mockCtx;
    private HttpRequest mockRequest;
    private Route route;
    private PluginContext context;

    @BeforeEach
    void setUp() {
        mockCtx = Mockito.mock(ChannelHandlerContext.class);
        mockRequest = Mockito.mock(HttpRequest.class);
        route = new Route();
        route.setId("test-route");
        context = new PluginContext(mockCtx, mockRequest, route, "trace-001");
    }

    @Test
    void constructor_setsReadOnlyFields() {
        assertThat(context.getChannelHandlerContext()).isSameAs(mockCtx);
        assertThat(context.getRequest()).isSameAs(mockRequest);
        assertThat(context.getRoute()).isSameAs(route);
        assertThat(context.getTraceId()).isEqualTo("trace-001");
    }

    @Test
    void clientIp_initiallyNull_thenSettable() {
        assertThat(context.getClientIp()).isNull();

        context.setClientIp("10.0.0.1");
        assertThat(context.getClientIp()).isEqualTo("10.0.0.1");
    }

    @Test
    void userId_initiallyNull_thenSettable() {
        assertThat(context.getUserId()).isNull();

        context.setUserId("user-42");
        assertThat(context.getUserId()).isEqualTo("user-42");
    }

    @Test
    void setAttribute_getAttribute_roundTrip() {
        context.setAttribute("authRequired", Boolean.TRUE);

        Boolean value = context.getAttribute("authRequired", Boolean.class);
        assertThat(value).isTrue();
    }

    @Test
    void getAttribute_missingKey_returnsNull() {
        assertThat(context.getAttribute("nonexistent", String.class)).isNull();
    }

    @Test
    void putTraceTag_appearsInTraceTags() {
        context.putTraceTag("ip-access", "CONTINUE");
        context.putTraceTag("auth", "SHORT_CIRCUIT");

        assertThat(context.getTraceTags())
                .containsEntry("ip-access", "CONTINUE")
                .containsEntry("auth", "SHORT_CIRCUIT")
                .hasSize(2);
    }

    @Test
    void putTraceTag_nullKeyOrValue_ignored() {
        context.putTraceTag(null, "value");
        context.putTraceTag("key", null);

        assertThat(context.getTraceTags()).isEmpty();
    }

    @Test
    void getTraceTags_returnsDefensiveCopy() {
        context.putTraceTag("plugin", "CONTINUE");

        context.getTraceTags().put("injected", "bad");

        assertThat(context.getTraceTags()).doesNotContainKey("injected");
        assertThat(context.getTraceTags()).hasSize(1);
    }
}
