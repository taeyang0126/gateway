package com.lei.gateway.core.plugin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.lei.gateway.core.config.Route;
import com.lei.gateway.core.security.RateLimitResult;
import com.lei.gateway.core.security.RateLimiterEngine;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class IpRateLimitPluginTest {

    private RateLimiterEngine rateLimiterEngine;
    private IpRateLimitPlugin plugin;
    private ChannelHandlerContext mockCtx;

    @BeforeEach
    void setUp() {
        rateLimiterEngine = mock(RateLimiterEngine.class);
        plugin = new IpRateLimitPlugin(rateLimiterEngine);
        mockCtx = mock(ChannelHandlerContext.class);
    }

    @Test
    void namePhaseAndPriority() {
        assertThat(plugin.name()).isEqualTo("ip-rate-limit");
        assertThat(plugin.phase()).isEqualTo(PluginPhase.REQUEST);
        assertThat(plugin.defaultPriority()).isEqualTo(3000);
    }

    @Test
    void allowedRequestReturnsContinue() {
        when(rateLimiterEngine.allow(eq("ip:10.0.0.1"), anyInt(), anyInt()))
                .thenReturn(RateLimitResult.allowed());

        PluginContext context = createContext("10.0.0.1");
        PluginConfig config = PluginConfig.of("ip-rate-limit", true, 3000,
                Map.of("permits-per-second", 100, "burst-capacity", 200), IpRateLimitPlugin.Config.class);

        PluginResult result = plugin.execute(context, config);

        assertThat(result.isContinue()).isTrue();
    }

    @Test
    void deniedRequestReturns429WithRetryAfter() {
        when(rateLimiterEngine.allow(eq("ip:10.0.0.1"), anyInt(), anyInt()))
                .thenReturn(RateLimitResult.denied(5));

        PluginContext context = createContext("10.0.0.1");
        PluginConfig config = PluginConfig.of("ip-rate-limit", true, 3000,
                Map.of("permits-per-second", 100, "burst-capacity", 200), IpRateLimitPlugin.Config.class);

        PluginResult result = plugin.execute(context, config);

        assertThat(result.getType()).isEqualTo(PluginResultType.SHORT_CIRCUIT);
        assertThat(result.getStatus()).isEqualTo(HttpResponseStatus.TOO_MANY_REQUESTS);
        assertThat(result.getRetryAfterSeconds()).isEqualTo(5);
        assertThat(result.getReason()).isEqualTo("rate_limited");
    }

    @Test
    void shadowModeDeniedReturnsContinue() {
        when(rateLimiterEngine.allow(eq("ip:10.0.0.1"), anyInt(), anyInt()))
                .thenReturn(RateLimitResult.denied(3));

        PluginContext context = createContext("10.0.0.1");
        PluginConfig config = PluginConfig.of("ip-rate-limit", true, 3000,
                Map.of("shadow", true, "permits-per-second", 100, "burst-capacity", 200), IpRateLimitPlugin.Config.class);

        PluginResult result = plugin.execute(context, config);

        assertThat(result.isContinue()).isTrue();
    }

    @Test
    void disabledReturnsContinue() {
        PluginContext context = createContext("10.0.0.1");
        PluginConfig config = PluginConfig.of("ip-rate-limit", true, 3000,
                Map.of("enabled", false), IpRateLimitPlugin.Config.class);

        PluginResult result = plugin.execute(context, config);

        assertThat(result.isContinue()).isTrue();
    }

    private PluginContext createContext(String clientIp) {
        Route route = new Route();
        route.setId("test-route");
        route.setPathPrefix("/api");
        route.setUpstream("http://localhost:8080");
        PluginContext context = new PluginContext(mockCtx,
                new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/api/test"),
                route, "trace-1");
        context.setClientIp(clientIp);
        return context;
    }
}
