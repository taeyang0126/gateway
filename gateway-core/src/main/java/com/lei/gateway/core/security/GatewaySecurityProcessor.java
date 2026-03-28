package com.lei.gateway.core.security;

import com.lei.gateway.core.config.Route;
import com.lei.gateway.core.config.SecurityProperties;
import com.lei.gateway.core.observability.MetricsCollector;
import com.lei.gateway.core.observability.TraceContextHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.util.AttributeKey;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 网关安全过滤流程处理器。
 */
public class GatewaySecurityProcessor {

    private static final Logger log = LoggerFactory.getLogger(GatewaySecurityProcessor.class);
    private static final String USER_ID_HEADER = "x-userId";

    /**
     * Channel Attribute key：认证后的用户标识。
     */
    public static final AttributeKey<String> USER_ID_KEY =
            AttributeKey.valueOf("userId");

    /**
     * Channel Attribute key：过滤阶段追踪标签。
     */
    public static final AttributeKey<Map<String, String>> SECURITY_TRACE_TAGS_KEY =
            AttributeKey.valueOf("securityTraceTags");

    private final RouteSecurityConfigResolver configResolver;
    private final ClientIpResolver clientIpResolver;
    private final CidrMatcher cidrMatcher;
    private final LocalTokenBucketRateLimiter localRateLimiter;
    private final DistributedRateLimiterAdapter distributedRateLimiter;
    private final MetricsCollector metricsCollector;
    private final SecurityAuditLogger securityAuditLogger;
    private final Map<SecurityProperties.AuthType, AuthProvider> authProviders;

    /**
     * 创建安全处理器。
     */
    public GatewaySecurityProcessor(SecurityProperties securityProperties,
            MetricsCollector metricsCollector) {
        this.configResolver = new RouteSecurityConfigResolver(securityProperties);
        this.clientIpResolver = new ClientIpResolver(new CidrMatcher());
        this.cidrMatcher = new CidrMatcher();
        this.localRateLimiter = new LocalTokenBucketRateLimiter();
        this.distributedRateLimiter = new DistributedRateLimiterAdapter();
        this.metricsCollector = metricsCollector;
        this.securityAuditLogger = new SecurityAuditLogger();

        this.authProviders = new HashMap<>();
        AuthProvider jwtProvider = new JwtAuthProvider(new JwksKeyProvider());
        this.authProviders.put(jwtProvider.type(), jwtProvider);
    }

    /**
     * 执行路由请求的安全过滤。
     */
    public SecurityEvaluationResult evaluate(ChannelHandlerContext ctx,
            io.netty.handler.codec.http.HttpRequest request, Route route) {
        String traceId = ctx.channel().attr(TraceContextHandler.TRACE_ID_KEY).get();
        EffectiveSecurityConfig config = configResolver.resolve(route);
        SecurityRequestContext context = new SecurityRequestContext(
                ctx, request, route, config, traceId);

        List<SecurityFilter> filters = buildFilters(config);
        for (SecurityFilter filter : filters) {
            long startNanos = System.nanoTime();
            SecurityDecision decision;
            try {
                decision = filter.apply(context);
            } catch (Exception ex) {
                log.error("安全过滤器执行异常 filter={} routeId={}",
                        filter.name(), route.getId(), ex);
                decision = SecurityDecision.error(
                        HttpResponseStatus.INTERNAL_SERVER_ERROR,
                        filter.name(), "filter_execution_error");
            }

            long duration = System.nanoTime() - startNanos;
            metricsCollector.recordSecurityFilterDuration(
                    filter.name(), route.getId(), duration);
            metricsCollector.recordSecurityFilterDecision(
                    filter.name(), decision.getType().name(),
                    decision.getReason(), route.getId());

            context.putTraceTag(filter.name(), decision.getType().name());
            context.putTraceTag(filter.name() + ".reason",
                    decision.getReason() == null ? "none" : decision.getReason());
            securityAuditLogger.log(context, decision);

            if (!decision.isAllowed()) {
                ctx.channel().attr(USER_ID_KEY).set(context.getUserId());
                ctx.channel().attr(SECURITY_TRACE_TAGS_KEY).set(context.getTraceTags());
                return new SecurityEvaluationResult(decision, context);
            }
        }

        ctx.channel().attr(USER_ID_KEY).set(context.getUserId());
        ctx.channel().attr(SECURITY_TRACE_TAGS_KEY).set(context.getTraceTags());
        return new SecurityEvaluationResult(
                SecurityDecision.allow("security-chain", "allow"),
                context);
    }

    private List<SecurityFilter> buildFilters(EffectiveSecurityConfig config) {
        List<SecurityFilter> filters = new ArrayList<>();
        filters.add(new RealIpFilter(config));
        if (!config.isEnabled()) {
            return filters;
        }
        filters.add(new IpAccessFilter(config));
        filters.add(new IpRateLimitFilter(config));
        filters.add(new AuthenticationFilter(config));
        filters.add(new UserRateLimitFilter(config));
        return filters;
    }

    private final class RealIpFilter implements SecurityFilter {

        private final EffectiveSecurityConfig config;

        private RealIpFilter(EffectiveSecurityConfig config) {
            this.config = config;
        }

        @Override
        public String name() {
            return "real-ip";
        }

        @Override
        public SecurityDecision apply(SecurityRequestContext context) {
            String clientIp = clientIpResolver.resolve(
                    context.getChannelHandlerContext(),
                    context.getRequest().headers(),
                    config.getTrustedProxies(),
                    config.getTrustedProxyHops());
            context.setClientIp(clientIp);
            return SecurityDecision.allow(name(), "resolved");
        }
    }

    private final class IpAccessFilter implements SecurityFilter {

        private final EffectiveSecurityConfig config;

        private IpAccessFilter(EffectiveSecurityConfig config) {
            this.config = config;
        }

        @Override
        public String name() {
            return "ip-access";
        }

        @Override
        public SecurityDecision apply(SecurityRequestContext context) {
            EffectiveSecurityConfig.IpAccess ipConfig = config.getIpAccess();
            if (!ipConfig.isEnabled()) {
                return SecurityDecision.allow(name(), "disabled");
            }

            String clientIp = context.getClientIp();
            if (matchesAny(clientIp, ipConfig.getDenyList())) {
                if (ipConfig.isShadow()) {
                    return SecurityDecision.allow(name(), "shadow_deny");
                }
                return SecurityDecision.deny(HttpResponseStatus.FORBIDDEN,
                        name(), "ip_in_deny_list");
            }
            if (!ipConfig.getAllowList().isEmpty()
                    && !matchesAny(clientIp, ipConfig.getAllowList())) {
                if (ipConfig.isShadow()) {
                    return SecurityDecision.allow(name(), "shadow_not_allow");
                }
                return SecurityDecision.deny(HttpResponseStatus.FORBIDDEN,
                        name(), "ip_not_in_allow_list");
            }
            return SecurityDecision.allow(name(), "allow");
        }

        private boolean matchesAny(String ip, List<String> rules) {
            for (String rule : rules) {
                if (cidrMatcher.matches(ip, rule)) {
                    return true;
                }
            }
            return false;
        }
    }

    private final class IpRateLimitFilter implements SecurityFilter {

        private final EffectiveSecurityConfig config;

        private IpRateLimitFilter(EffectiveSecurityConfig config) {
            this.config = config;
        }

        @Override
        public String name() {
            return "ip-rate-limit";
        }

        @Override
        public SecurityDecision apply(SecurityRequestContext context) {
            EffectiveSecurityConfig.IpRateLimit rateLimit = config.getRateLimit().getIp();
            if (!rateLimit.isEnabled()) {
                return SecurityDecision.allow(name(), "disabled");
            }
            String key = "ip:" + context.getClientIp();
            RateLimitResult result = checkRateLimit(
                    rateLimit, key,
                    name(), context);
            if (result.isAllowed()) {
                return SecurityDecision.allow(name(), "allow");
            }
            if (rateLimit.isShadow()) {
                return SecurityDecision.allow(name(), "shadow_limited");
            }
            metricsCollector.recordRateLimitHit(name(), context.getRouteId());
            return SecurityDecision.denyWithRetry(
                    HttpResponseStatus.TOO_MANY_REQUESTS,
                    name(), "rate_limited", result.getRetryAfterSeconds());
        }
    }

    private final class AuthenticationFilter implements SecurityFilter {

        private final EffectiveSecurityConfig config;

        private AuthenticationFilter(EffectiveSecurityConfig config) {
            this.config = config;
        }

        @Override
        public String name() {
            return "auth";
        }

        @Override
        public SecurityDecision apply(SecurityRequestContext context) {
            EffectiveSecurityConfig.Auth authConfig = config.getAuth();
            if (!authConfig.isEnabled()) {
                return SecurityDecision.allow(name(), "disabled");
            }
            context.getRequest().headers().remove(USER_ID_HEADER);
            AuthProvider authProvider = authProviders.get(authConfig.getType());
            if (authProvider == null) {
                metricsCollector.recordAuthFailure("provider_not_found");
                if (authConfig.isFailClosed()) {
                    return SecurityDecision.deny(HttpResponseStatus.UNAUTHORIZED,
                            name(), "auth_provider_not_found");
                }
                return SecurityDecision.allow(name(), "fail_open_provider_missing");
            }

            AuthenticationResult authResult;
            try {
                authResult = authProvider.authenticate(
                        context, authConfig);
            } catch (Exception ex) {
                log.error("认证提供方执行异常 providerType={} routeId={}",
                        authConfig.getType(), context.getRouteId(), ex);
                metricsCollector.recordAuthFailure("provider_exception");
                if (authConfig.isShadow()) {
                    return SecurityDecision.allow(name(), "shadow_auth_provider_error");
                }
                if (authConfig.isFailClosed()) {
                    return SecurityDecision.deny(HttpResponseStatus.UNAUTHORIZED,
                            name(), "auth_provider_error");
                }
                return SecurityDecision.allow(name(), "fail_open_auth_provider_error");
            }
            if (authResult.isAuthenticated()) {
                context.setUserId(authResult.getUserId());
                if (authResult.getUserId() != null
                        && !authResult.getUserId().isBlank()) {
                    context.getRequest().headers().set(
                            USER_ID_HEADER, authResult.getUserId());
                }
                context.getRequest().headers().remove(
                        authConfig.getTokenExtractor().getTokenHeaderName());
                return SecurityDecision.allow(name(), "authenticated");
            }

            metricsCollector.recordAuthFailure(authResult.getReason());
            if (authConfig.isShadow()) {
                return SecurityDecision.allow(name(), "shadow_auth_failed");
            }
            if (authConfig.isFailClosed()) {
                return SecurityDecision.deny(HttpResponseStatus.UNAUTHORIZED,
                        name(), authResult.getReason());
            }
            return SecurityDecision.allow(name(), "fail_open_auth_failed");
        }
    }

    private final class UserRateLimitFilter implements SecurityFilter {

        private final EffectiveSecurityConfig config;

        private UserRateLimitFilter(EffectiveSecurityConfig config) {
            this.config = config;
        }

        @Override
        public String name() {
            return "user-rate-limit";
        }

        @Override
        public SecurityDecision apply(SecurityRequestContext context) {
            EffectiveSecurityConfig.UserRateLimit rateLimit = config.getRateLimit().getUser();
            if (!rateLimit.isEnabled()) {
                return SecurityDecision.allow(name(), "disabled");
            }
            String userId = context.getUserId();
            if (userId == null || userId.isBlank()) {
                return SecurityDecision.allow(name(), "skip_no_user");
            }
            String key = "user:" + userId;
            RateLimitResult result = checkRateLimit(
                    rateLimit, key,
                    name(), context);
            if (result.isAllowed()) {
                return SecurityDecision.allow(name(), "allow");
            }
            if (rateLimit.isShadow()) {
                return SecurityDecision.allow(name(), "shadow_limited");
            }
            metricsCollector.recordRateLimitHit(name(), context.getRouteId());
            return SecurityDecision.denyWithRetry(
                    HttpResponseStatus.TOO_MANY_REQUESTS,
                    name(), "rate_limited", result.getRetryAfterSeconds());
        }
    }

    private RateLimitResult checkRateLimit(
            EffectiveSecurityConfig.BaseRateLimit rateLimit,
            String key,
            String stage, SecurityRequestContext context) {
        if (rateLimit.getMode() == SecurityProperties.RateLimitMode.DISTRIBUTED) {
            try {
                return distributedRateLimiter.allow(
                        key, rateLimit.getPermitsPerSecond(), rateLimit.getBurstCapacity());
            } catch (Exception ex) {
                metricsCollector.recordSecurityFallback(
                        "rate-limit", "distributed_to_local");
                log.warn("分布式限流失败，降级本地限流 stage={} routeId={}",
                        stage, context.getRouteId());
            }
        }
        return localRateLimiter.allow(
                key, rateLimit.getPermitsPerSecond(), rateLimit.getBurstCapacity());
    }
}
