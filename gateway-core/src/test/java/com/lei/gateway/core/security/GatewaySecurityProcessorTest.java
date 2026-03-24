package com.lei.gateway.core.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.lei.gateway.core.config.ObservabilityProperties;
import com.lei.gateway.core.config.Route;
import com.lei.gateway.core.config.SecurityProperties;
import com.lei.gateway.core.observability.MetricsCollector;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class GatewaySecurityProcessorTest {

    @Test
    void buildFiltersShouldFollowFixedOrderWhenSecurityEnabled() throws Exception {
        GatewaySecurityProcessor processor = createProcessor();
        EffectiveSecurityConfig config = createConfig(true);

        List<SecurityFilter> filters = invokeBuildFilters(processor, config);

        assertThat(filterNames(filters)).containsExactly(
                "real-ip",
                "ip-access",
                "ip-rate-limit",
                "auth",
                "user-rate-limit");
    }

    @Test
    void buildFiltersShouldKeepRealIpOnlyWhenSecurityDisabled() throws Exception {
        GatewaySecurityProcessor processor = createProcessor();
        EffectiveSecurityConfig config = createConfig(false);

        List<SecurityFilter> filters = invokeBuildFilters(processor, config);

        assertThat(filterNames(filters)).containsExactly("real-ip");
    }

    @Test
    void evaluateShouldDeny401WhenAuthProviderThrowsAndFailClosedEnabled()
            throws Exception {
        SecurityProperties properties = new SecurityProperties();
        properties.setEnabled(true);
        properties.getAuth().setEnabled(true);
        properties.getAuth().setFailClosed(true);

        GatewaySecurityProcessor processor = createProcessor(properties);
        overrideJwtProvider(processor, new AuthProvider() {
            @Override
            public SecurityProperties.AuthType type() {
                return SecurityProperties.AuthType.JWT;
            }

            @Override
            public AuthenticationResult authenticate(SecurityRequestContext context,
                    EffectiveSecurityConfig.Auth authConfig) {
                throw new IllegalStateException("simulated provider error");
            }
        });

        Route route = new Route();
        route.setId("r1");
        route.setPathPrefix("/api/**");
        route.setUpstream("http://localhost:8081");

        DefaultFullHttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.GET, "/api/demo");
        EmbeddedChannel channel = new EmbeddedChannel(
                new ChannelInboundHandlerAdapter() {
                });
        ChannelHandlerContext ctx = channel.pipeline().firstContext();

        SecurityEvaluationResult result = processor.evaluate(ctx, request, route);

        assertThat(result.getDecision().isAllowed()).isFalse();
        assertThat(result.getDecision().getStatus())
                .isEqualTo(HttpResponseStatus.UNAUTHORIZED);
        assertThat(result.getDecision().getFilterName()).isEqualTo("auth");
        assertThat(result.getDecision().getReason()).isEqualTo("auth_provider_error");
        channel.finishAndReleaseAll();
    }

    private static GatewaySecurityProcessor createProcessor() {
        return createProcessor(new SecurityProperties());
    }

    private static GatewaySecurityProcessor createProcessor(
            SecurityProperties properties) {
        MetricsCollector metricsCollector = new MetricsCollector(
                new SimpleMeterRegistry(), new ObservabilityProperties());
        return new GatewaySecurityProcessor(properties, metricsCollector);
    }

    private static EffectiveSecurityConfig createConfig(boolean enabled) {
        return new EffectiveSecurityConfig(
                enabled,
                List.of(),
                null,
                new EffectiveSecurityConfig.IpAccess(false, false, true,
                        List.of(), List.of()),
                new EffectiveSecurityConfig.Auth(false, false, true,
                        SecurityProperties.AuthType.JWT,
                        new EffectiveSecurityConfig.TokenExtractor(
                                "Authorization", "Bearer "),
                        new EffectiveSecurityConfig.Providers(
                                new EffectiveSecurityConfig.Jwt(
                                        null, null, null, null, 300, 500, 1000))),
                new EffectiveSecurityConfig.RateLimit(
                        new EffectiveSecurityConfig.IpRateLimit(false, false,
                                SecurityProperties.RateLimitMode.LOCAL,
                                100, 100, 50),
                        new EffectiveSecurityConfig.UserRateLimit(false, false,
                                SecurityProperties.RateLimitMode.LOCAL,
                                100, 100, 50)));
    }

    @SuppressWarnings("unchecked")
    private static List<SecurityFilter> invokeBuildFilters(
            GatewaySecurityProcessor processor, EffectiveSecurityConfig config)
            throws Exception {
        Method method = GatewaySecurityProcessor.class.getDeclaredMethod(
                "buildFilters", EffectiveSecurityConfig.class);
        method.setAccessible(true);
        return (List<SecurityFilter>) method.invoke(processor, config);
    }

    private static List<String> filterNames(List<SecurityFilter> filters) {
        return filters.stream()
                .map(SecurityFilter::name)
                .collect(Collectors.toList());
    }

    @SuppressWarnings("unchecked")
    private static void overrideJwtProvider(GatewaySecurityProcessor processor,
            AuthProvider authProvider) throws Exception {
        Field field = GatewaySecurityProcessor.class.getDeclaredField(
                "authProviders");
        field.setAccessible(true);
        Map<SecurityProperties.AuthType, AuthProvider> providers =
                (Map<SecurityProperties.AuthType, AuthProvider>) field.get(processor);
        providers.put(SecurityProperties.AuthType.JWT, authProvider);
    }
}
