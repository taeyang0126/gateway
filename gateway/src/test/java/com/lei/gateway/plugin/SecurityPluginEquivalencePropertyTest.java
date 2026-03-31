package com.lei.gateway.plugin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.lei.gateway.config.Route;
import com.lei.gateway.config.SecurityProperties;
import com.lei.gateway.observability.MetricsCollector;
import com.lei.gateway.security.AuthProvider;
import com.lei.gateway.security.AuthenticationResult;
import com.lei.gateway.security.CidrMatcher;
import com.lei.gateway.security.ClientIpResolver;
import com.lei.gateway.security.EffectiveSecurityConfig;
import com.lei.gateway.security.RateLimitResult;
import com.lei.gateway.security.RateLimiterEngine;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Feature: plugin-system, Property 9: 安全插件行为等价性。
 *
 * <p>验证 5 个安全插件通过 PluginChain 执行后产生的最终决策与原 GatewaySecurityProcessor
 * 对相同输入产生的决策等价。通过控制底层组件（CidrMatcher、RateLimiterEngine、AuthProvider）
 * 的行为来验证插件层的决策逻辑等价性。
 */
class SecurityPluginEquivalencePropertyTest {

    // CHECKSTYLE.OFF: VisibilityModifier
    static class SecurityScenario {
        boolean ipInDenyList;
        boolean ipInAllowList;
        boolean hasAllowList;
        boolean ipRateLimited;
        boolean authSuccess;
        String authUserId;
        String authFailReason;
        boolean userRateLimited;
    }
    // CHECKSTYLE.ON: VisibilityModifier

    @Provide
    Arbitrary<SecurityScenario> securityScenarios() {
        Arbitrary<Boolean> bools = Arbitraries.of(true, false);
        Arbitrary<String> userIds = Arbitraries.strings().alpha().ofMinLength(3).ofMaxLength(10);
        Arbitrary<String> failReasons = Arbitraries.of(
                "invalid_signature", "token_expired", "missing_subject");

        return Combinators.combine(bools, bools, bools, bools, bools, userIds, failReasons, bools)
                .as((ipDeny, ipAllow, hasAllow, ipLimited, authOk,
                        userId, failReason, userLimited) -> {
                    SecurityScenario sc = new SecurityScenario();
                    sc.ipInDenyList = ipDeny;
                    sc.ipInAllowList = ipAllow;
                    sc.hasAllowList = hasAllow;
                    sc.ipRateLimited = ipLimited;
                    sc.authSuccess = authOk;
                    sc.authUserId = userId;
                    sc.authFailReason = failReason;
                    sc.userRateLimited = userLimited;
                    return sc;
                });
    }

    /**
     * 对于任意安全配置组合，插件链执行后的最终决策（CONTINUE 或 SHORT_CIRCUIT 及其状态码）
     * 应与原过滤器链对相同输入产生的决策等价。
     */
    @Property(tries = 100)
    // Feature: plugin-system, Property 9: 安全插件行为等价性
    void pluginChainDecisionEqualsSecurityFilterDecision(
            @ForAll("securityScenarios") SecurityScenario scenario) {

        ExpectedDecision expected = computeExpectedDecision(scenario);
        PluginResult actual = executePluginChain(scenario);

        if (expected.allowed) {
            assertThat(actual.isContinue())
                    .as("Expected CONTINUE: %s", describeScenario(scenario))
                    .isTrue();
        } else {
            assertThat(actual.isContinue())
                    .as("Expected SHORT_CIRCUIT: %s", describeScenario(scenario))
                    .isFalse();
            assertThat(actual.getStatus())
                    .as("Status mismatch: %s", describeScenario(scenario))
                    .isEqualTo(expected.status);
        }
    }

    private PluginResult executePluginChain(SecurityScenario scenario) {
        MetricsCollector metrics = mock(MetricsCollector.class);
        PluginChain chain = new PluginChain(metrics);

        CidrMatcher cidrMatcher = mock(CidrMatcher.class);
        when(cidrMatcher.matches("192.168.1.50", "10.0.0.0/8"))
                .thenReturn(scenario.ipInDenyList);
        when(cidrMatcher.matches("192.168.1.50", "192.168.1.0/24"))
                .thenReturn(scenario.ipInAllowList);

        ClientIpResolver ipResolver = mock(ClientIpResolver.class);
        when(ipResolver.resolve(any(), any(), any(), any())).thenReturn("192.168.1.50");

        RateLimiterEngine ipEngine = mock(RateLimiterEngine.class);
        when(ipEngine.allow(anyString(), anyInt(), anyInt()))
                .thenReturn(scenario.ipRateLimited
                        ? RateLimitResult.denied(1) : RateLimitResult.allowed());

        AuthProvider authProvider = mock(AuthProvider.class);
        when(authProvider.type()).thenReturn(SecurityProperties.AuthType.JWT);
        when(authProvider.authenticate(any(HttpRequest.class),
                any(EffectiveSecurityConfig.Auth.class)))
                .thenReturn(scenario.authSuccess
                        ? AuthenticationResult.success(scenario.authUserId)
                        : AuthenticationResult.failed(scenario.authFailReason));

        RateLimiterEngine userEngine = mock(RateLimiterEngine.class);
        when(userEngine.allow(anyString(), anyInt(), anyInt()))
                .thenReturn(scenario.userRateLimited
                        ? RateLimitResult.denied(1) : RateLimitResult.allowed());

        List<Plugin> plugins = List.of(
                new RealIpPlugin(ipResolver),
                new IpAccessPlugin(cidrMatcher),
                new IpRateLimitPlugin(ipEngine),
                new AuthPlugin(authProvider),
                new UserRateLimitPlugin(userEngine));

        Map<String, PluginConfig> configs = new HashMap<>();
        configs.put("real-ip", PluginConfig.of("real-ip", true, 1000, Map.of(), RealIpPlugin.Config.class));

        Map<String, Object> ipAccessCfg = new HashMap<>();
        ipAccessCfg.put("deny-list", List.of("10.0.0.0/8"));
        if (scenario.hasAllowList) {
            ipAccessCfg.put("allow-list", List.of("192.168.1.0/24"));
        }
        configs.put("ip-access", PluginConfig.of("ip-access", true, 2000, ipAccessCfg,
                IpAccessPlugin.Config.class));
        configs.put("ip-rate-limit", PluginConfig.of("ip-rate-limit", true, 3000,
                Map.of("permits-per-second", 100, "burst-capacity", 200), IpRateLimitPlugin.Config.class));
        configs.put("auth", PluginConfig.of("auth", true, 4000,
                Map.of("fail-closed", true), AuthPlugin.Config.class));
        configs.put("user-rate-limit", PluginConfig.of("user-rate-limit", true, 5000,
                Map.of("permits-per-second", 50, "burst-capacity", 100), UserRateLimitPlugin.Config.class));

        PluginContext context = createPluginContext();
        return chain.execute(plugins, configs, context, "test-route");
    }

    // CHECKSTYLE.OFF: VisibilityModifier
    static class ExpectedDecision {
        boolean allowed;
        HttpResponseStatus status;
    }
    // CHECKSTYLE.ON: VisibilityModifier

    private ExpectedDecision computeExpectedDecision(SecurityScenario scenario) {
        ExpectedDecision decision = new ExpectedDecision();

        // ip-access: deny 优先于 allow
        if (scenario.ipInDenyList) {
            decision.status = HttpResponseStatus.FORBIDDEN;
            return decision;
        }
        if (scenario.hasAllowList && !scenario.ipInAllowList) {
            decision.status = HttpResponseStatus.FORBIDDEN;
            return decision;
        }

        // ip-rate-limit
        if (scenario.ipRateLimited) {
            decision.status = HttpResponseStatus.TOO_MANY_REQUESTS;
            return decision;
        }

        // auth
        if (!scenario.authSuccess) {
            decision.status = HttpResponseStatus.UNAUTHORIZED;
            return decision;
        }

        // user-rate-limit（认证成功才有 userId）
        if (scenario.userRateLimited) {
            decision.status = HttpResponseStatus.TOO_MANY_REQUESTS;
            return decision;
        }

        decision.allowed = true;
        return decision;
    }

    private PluginContext createPluginContext() {
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);
        Channel channel = mock(Channel.class);
        when(ctx.channel()).thenReturn(channel);
        Route route = new Route();
        route.setId("test-route");
        route.setPathPrefix("/api");
        route.setUpstream("http://localhost:8080");
        DefaultHttpRequest request = new DefaultHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.GET, "/api/test");
        request.headers().set("Authorization", "Bearer test-token");
        return new PluginContext(ctx, request, route, "trace-eq");
    }

    private static String describeScenario(SecurityScenario sc) {
        return String.format(Locale.ROOT, "deny=%s allow=%s hasList=%s ipLimit=%s authOk=%s userLimit=%s",
                sc.ipInDenyList, sc.ipInAllowList, sc.hasAllowList,
                sc.ipRateLimited, sc.authSuccess, sc.userRateLimited);
    }
}
