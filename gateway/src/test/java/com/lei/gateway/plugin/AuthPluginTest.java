package com.lei.gateway.plugin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.lei.gateway.config.Route;
import com.lei.gateway.config.SecurityProperties;
import com.lei.gateway.security.AuthProvider;
import com.lei.gateway.security.AuthenticationResult;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AuthPluginTest {

    private AuthProvider authProvider;
    private AuthPlugin plugin;
    private ChannelHandlerContext mockCtx;

    @BeforeEach
    void setUp() {
        authProvider = mock(AuthProvider.class);
        when(authProvider.type()).thenReturn(SecurityProperties.AuthType.JWT);
        plugin = new AuthPlugin(authProvider);
        mockCtx = mock(ChannelHandlerContext.class);
    }

    @Test
    void namePhaseAndPriority() {
        assertThat(plugin.name()).isEqualTo("auth");
        assertThat(plugin.phase()).isEqualTo(PluginPhase.REQUEST);
        assertThat(plugin.defaultPriority()).isEqualTo(4000);
    }

    @Test
    void successfulAuthSetsUserId() {
        when(authProvider.authenticate(any(), any()))
                .thenReturn(AuthenticationResult.success("user-123"));

        PluginContext context = createContextWithAuthHeader("Bearer valid-token");
        PluginConfig config = createAuthConfig();

        PluginResult result = plugin.execute(context, config);

        assertThat(result.isContinue()).isTrue();
        assertThat(context.getUserId()).isEqualTo("user-123");
        assertThat(context.getAttribute("authRequired", Boolean.class)).isTrue();
        assertThat(context.getAttribute("authPassed", Boolean.class)).isTrue();
    }

    @Test
    void failedAuthReturns401() {
        when(authProvider.authenticate(any(), any()))
                .thenReturn(AuthenticationResult.failed("invalid_signature"));

        PluginContext context = createContextWithAuthHeader("Bearer bad-token");
        PluginConfig config = createAuthConfig();

        PluginResult result = plugin.execute(context, config);

        assertThat(result.getType()).isEqualTo(PluginResultType.SHORT_CIRCUIT);
        assertThat(result.getStatus()).isEqualTo(HttpResponseStatus.UNAUTHORIZED);
        assertThat(result.getReason()).isEqualTo("invalid_signature");
        assertThat(context.getAttribute("authRequired", Boolean.class)).isTrue();
        assertThat(context.getAttribute("authPassed", Boolean.class)).isFalse();
    }

    @Test
    void shadowModeFailedAuthReturnsContinue() {
        when(authProvider.authenticate(any(), any()))
                .thenReturn(AuthenticationResult.failed("token_expired"));

        PluginContext context = createContextWithAuthHeader("Bearer expired-token");
        PluginConfig config = PluginConfig.of("auth", true, 4000,
                Map.of("shadow", true, "fail-closed", true), AuthPlugin.Config.class);

        PluginResult result = plugin.execute(context, config);

        assertThat(result.isContinue()).isTrue();
        assertThat(context.getAttribute("authPassed", Boolean.class)).isFalse();
    }

    @Test
    void disabledAuthReturnsContinue() {
        PluginContext context = createContextWithAuthHeader("Bearer any-token");
        PluginConfig config = PluginConfig.of("auth", true, 4000,
                Map.of("enabled", false), AuthPlugin.Config.class);

        PluginResult result = plugin.execute(context, config);

        assertThat(result.isContinue()).isTrue();
        assertThat(context.getAttribute("authRequired", Boolean.class)).isFalse();
    }

    @Test
    void providerExceptionWithFailClosedReturns401() {
        when(authProvider.authenticate(any(), any()))
                .thenThrow(new RuntimeException("simulated error"));

        PluginContext context = createContextWithAuthHeader("Bearer token");
        PluginConfig config = createAuthConfig();

        PluginResult result = plugin.execute(context, config);

        assertThat(result.getType()).isEqualTo(PluginResultType.SHORT_CIRCUIT);
        assertThat(result.getStatus()).isEqualTo(HttpResponseStatus.UNAUTHORIZED);
        assertThat(result.getReason()).isEqualTo("auth_provider_error");
    }

    @Test
    void providerExceptionWithShadowReturnsContinue() {
        when(authProvider.authenticate(any(), any()))
                .thenThrow(new RuntimeException("simulated error"));

        PluginContext context = createContextWithAuthHeader("Bearer token");
        PluginConfig config = PluginConfig.of("auth", true, 4000,
                Map.of("shadow", true), AuthPlugin.Config.class);

        PluginResult result = plugin.execute(context, config);

        assertThat(result.isContinue()).isTrue();
    }

    private PluginConfig createAuthConfig() {
        return PluginConfig.of("auth", true, 4000,
                Map.of("fail-closed", true), AuthPlugin.Config.class);
    }

    private PluginContext createContextWithAuthHeader(String authHeader) {
        Route route = new Route();
        route.setId("test-route");
        route.setPathPrefix("/api");
        route.setUpstream("http://localhost:8080");
        DefaultHttpRequest request = new DefaultHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.GET, "/api/test");
        if (authHeader != null) {
            request.headers().set("Authorization", authHeader);
        }
        return new PluginContext(mockCtx, request, route, "trace-1");
    }
}
