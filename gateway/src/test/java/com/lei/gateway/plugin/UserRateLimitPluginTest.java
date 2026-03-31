package com.lei.gateway.plugin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lei.gateway.config.Route;
import com.lei.gateway.security.RateLimitResult;
import com.lei.gateway.security.RateLimiterEngine;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class UserRateLimitPluginTest {

    private RateLimiterEngine rateLimiterEngine;
    private UserRateLimitPlugin plugin;
    private ChannelHandlerContext mockCtx;

    @BeforeEach
    void setUp() {
        rateLimiterEngine = mock(RateLimiterEngine.class);
        plugin = new UserRateLimitPlugin(rateLimiterEngine);
        mockCtx = mock(ChannelHandlerContext.class);
    }

    @Test
    void namePhaseAndPriority() {
        assertThat(plugin.name()).isEqualTo("user-rate-limit");
        assertThat(plugin.phase()).isEqualTo(PluginPhase.REQUEST);
        assertThat(plugin.defaultPriority()).isEqualTo(5000);
    }

    @Test
    void allowedUserReturnsContinue() {
        when(rateLimiterEngine.allow(eq("user:user-123"), anyInt(), anyInt()))
                .thenReturn(RateLimitResult.allowed());

        PluginContext context = createContext("user-123");
        PluginConfig config = PluginConfig.of("user-rate-limit", true, 5000,
                Map.of("permits-per-second", 50, "burst-capacity", 100), UserRateLimitPlugin.Config.class);

        PluginResult result = plugin.execute(context, config);

        assertThat(result.isContinue()).isTrue();
    }

    @Test
    void deniedUserReturns429WithRetryAfter() {
        when(rateLimiterEngine.allow(eq("user:user-123"), anyInt(), anyInt()))
                .thenReturn(RateLimitResult.denied(10));

        PluginContext context = createContext("user-123");
        PluginConfig config = PluginConfig.of("user-rate-limit", true, 5000,
                Map.of("permits-per-second", 50, "burst-capacity", 100), UserRateLimitPlugin.Config.class);

        PluginResult result = plugin.execute(context, config);

        assertThat(result.getType()).isEqualTo(PluginResultType.SHORT_CIRCUIT);
        assertThat(result.getStatus()).isEqualTo(HttpResponseStatus.TOO_MANY_REQUESTS);
        assertThat(result.getRetryAfterSeconds()).isEqualTo(10);
        assertThat(result.getReason()).isEqualTo("rate_limited");
    }

    @Test
    void noUserIdSkipsRateLimit() {
        PluginContext context = createContext(null);
        PluginConfig config = PluginConfig.of("user-rate-limit", true, 5000,
                Map.of("permits-per-second", 50, "burst-capacity", 100), UserRateLimitPlugin.Config.class);

        PluginResult result = plugin.execute(context, config);

        assertThat(result.isContinue()).isTrue();
        verify(rateLimiterEngine, never()).allow(any(), anyInt(), anyInt());
    }

    @Test
    void blankUserIdSkipsRateLimit() {
        PluginContext context = createContext("  ");
        PluginConfig config = PluginConfig.of("user-rate-limit", true, 5000,
                Map.of("permits-per-second", 50, "burst-capacity", 100), UserRateLimitPlugin.Config.class);

        PluginResult result = plugin.execute(context, config);

        assertThat(result.isContinue()).isTrue();
        verify(rateLimiterEngine, never()).allow(any(), anyInt(), anyInt());
    }

    @Test
    void shadowModeDeniedReturnsContinue() {
        when(rateLimiterEngine.allow(eq("user:user-123"), anyInt(), anyInt()))
                .thenReturn(RateLimitResult.denied(5));

        PluginContext context = createContext("user-123");
        PluginConfig config = PluginConfig.of("user-rate-limit", true, 5000,
                Map.of("shadow", true, "permits-per-second", 50, "burst-capacity", 100), UserRateLimitPlugin.Config.class);

        PluginResult result = plugin.execute(context, config);

        assertThat(result.isContinue()).isTrue();
    }

    @Test
    void disabledReturnsContinue() {
        PluginContext context = createContext("user-123");
        PluginConfig config = PluginConfig.of("user-rate-limit", true, 5000,
                Map.of("enabled", false), UserRateLimitPlugin.Config.class);

        PluginResult result = plugin.execute(context, config);

        assertThat(result.isContinue()).isTrue();
        verify(rateLimiterEngine, never()).allow(any(), anyInt(), anyInt());
    }

    private PluginContext createContext(String userId) {
        Route route = new Route();
        route.setId("test-route");
        route.setPathPrefix("/api");
        route.setUpstream("http://localhost:8080");
        PluginContext context = new PluginContext(mockCtx,
                new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/api/test"),
                route, "trace-1");
        context.setUserId(userId);
        return context;
    }

    private static String any() {
        return org.mockito.ArgumentMatchers.any();
    }
}
