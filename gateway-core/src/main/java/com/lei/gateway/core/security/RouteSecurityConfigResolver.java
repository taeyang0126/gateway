package com.lei.gateway.core.security;

import com.lei.gateway.core.config.Route;
import com.lei.gateway.core.config.RouteSecurityProperties;
import com.lei.gateway.core.config.SecurityProperties;
import java.util.List;

/**
 * 计算路由请求的生效安全配置。
 */
public class RouteSecurityConfigResolver {

    private final SecurityProperties globalSecurityProperties;

    /**
     * 创建路由安全配置解析器。
     */
    public RouteSecurityConfigResolver(SecurityProperties globalSecurityProperties) {
        this.globalSecurityProperties = globalSecurityProperties;
    }

    /**
     * 解析路由生效配置。
     */
    public EffectiveSecurityConfig resolve(Route route) {
        RouteSecurityProperties routeSecurity = route.getSecurity();
        if (routeSecurity == null) {
            return fromGlobal(globalSecurityProperties);
        }

        SecurityProperties.MergeMode mode = routeSecurity.getMode();
        SecurityProperties base = mode == SecurityProperties.MergeMode.REPLACE
                ? new SecurityProperties() : globalSecurityProperties;

        boolean enabled = choose(routeSecurity.getEnabled(), base.isEnabled());
        List<String> trustedProxies = chooseList(routeSecurity.getTrustedProxies(),
                base.getTrustedProxies());
        Integer trustedProxyHops = choose(routeSecurity.getTrustedProxyHops(),
                base.getTrustedProxyHops());

        EffectiveSecurityConfig.IpAccess ipAccess =
                mergeIpAccess(base.getIpAccess(), routeSecurity.getIpAccess());
        EffectiveSecurityConfig.Auth auth =
                mergeAuth(base.getAuth(), routeSecurity.getAuth());
        EffectiveSecurityConfig.RateLimit rateLimit =
                mergeRateLimit(base.getRateLimit(), routeSecurity.getRateLimit());

        return new EffectiveSecurityConfig(enabled, trustedProxies, trustedProxyHops,
                ipAccess, auth, rateLimit);
    }

    private static EffectiveSecurityConfig fromGlobal(SecurityProperties global) {
        EffectiveSecurityConfig.IpAccess ipAccess =
                new EffectiveSecurityConfig.IpAccess(
                        global.getIpAccess().isEnabled(),
                        global.getIpAccess().isShadow(),
                        global.getIpAccess().isFailClosed(),
                        global.getIpAccess().getAllowList(),
                        global.getIpAccess().getDenyList());

        EffectiveSecurityConfig.Auth auth = new EffectiveSecurityConfig.Auth(
                global.getAuth().isEnabled(),
                global.getAuth().isShadow(),
                global.getAuth().isFailClosed(),
                global.getAuth().getType(),
                mergeTokenExtractor(global.getAuth().getTokenExtractor(), null),
                mergeAuthProviders(global.getAuth().getProviders(), null));

        EffectiveSecurityConfig.RateLimit rateLimit =
                new EffectiveSecurityConfig.RateLimit(
                        mergeIpRateLimit(global.getRateLimit().getIp(), null),
                        mergeUserRateLimit(global.getRateLimit().getUser(), null));

        return new EffectiveSecurityConfig(global.isEnabled(),
                global.getTrustedProxies(), global.getTrustedProxyHops(),
                ipAccess, auth, rateLimit);
    }

    private static EffectiveSecurityConfig.IpAccess mergeIpAccess(
            SecurityProperties.IpAccessProperties globalIpAccess,
            RouteSecurityProperties.RouteIpAccessProperties routeIpAccess) {
        if (routeIpAccess == null) {
            return new EffectiveSecurityConfig.IpAccess(
                    globalIpAccess.isEnabled(), globalIpAccess.isShadow(),
                    globalIpAccess.isFailClosed(),
                    globalIpAccess.getAllowList(), globalIpAccess.getDenyList());
        }
        return new EffectiveSecurityConfig.IpAccess(
                choose(routeIpAccess.getEnabled(), globalIpAccess.isEnabled()),
                choose(routeIpAccess.getShadow(), globalIpAccess.isShadow()),
                choose(routeIpAccess.getFailClosed(), globalIpAccess.isFailClosed()),
                chooseList(routeIpAccess.getAllowList(), globalIpAccess.getAllowList()),
                chooseList(routeIpAccess.getDenyList(), globalIpAccess.getDenyList()));
    }

    private static EffectiveSecurityConfig.Auth mergeAuth(
            SecurityProperties.AuthProperties globalAuth,
            RouteSecurityProperties.RouteAuthProperties routeAuth) {
        RouteSecurityProperties.RouteTokenExtractorProperties routeTokenExtractor =
                routeAuth == null ? null : routeAuth.getTokenExtractor();
        RouteSecurityProperties.RouteAuthProvidersProperties routeProviders =
                routeAuth == null ? null : routeAuth.getProviders();
        return new EffectiveSecurityConfig.Auth(
                choose(routeAuth == null ? null : routeAuth.getEnabled(),
                        globalAuth.isEnabled()),
                choose(routeAuth == null ? null : routeAuth.getShadow(),
                        globalAuth.isShadow()),
                choose(routeAuth == null ? null : routeAuth.getFailClosed(),
                        globalAuth.isFailClosed()),
                choose(routeAuth == null ? null : routeAuth.getType(),
                        globalAuth.getType()),
                mergeTokenExtractor(globalAuth.getTokenExtractor(), routeTokenExtractor),
                mergeAuthProviders(globalAuth.getProviders(), routeProviders));
    }

    private static EffectiveSecurityConfig.TokenExtractor mergeTokenExtractor(
            SecurityProperties.TokenExtractorProperties globalTokenExtractor,
            RouteSecurityProperties.RouteTokenExtractorProperties routeTokenExtractor) {
        if (routeTokenExtractor == null) {
            return new EffectiveSecurityConfig.TokenExtractor(
                    globalTokenExtractor.getTokenHeaderName(),
                    globalTokenExtractor.getTokenValuePrefix());
        }
        return new EffectiveSecurityConfig.TokenExtractor(
                choose(routeTokenExtractor.getTokenHeaderName(),
                        globalTokenExtractor.getTokenHeaderName()),
                choose(routeTokenExtractor.getTokenValuePrefix(),
                        globalTokenExtractor.getTokenValuePrefix()));
    }

    private static EffectiveSecurityConfig.Providers mergeAuthProviders(
            SecurityProperties.AuthProvidersProperties globalProviders,
            RouteSecurityProperties.RouteAuthProvidersProperties routeProviders) {
        return new EffectiveSecurityConfig.Providers(
                mergeJwt(globalProviders.getJwt(),
                        routeProviders == null ? null : routeProviders.getJwt()));
    }

    private static EffectiveSecurityConfig.Jwt mergeJwt(
            SecurityProperties.JwtProperties globalJwt,
            RouteSecurityProperties.RouteJwtProperties routeJwt) {
        if (routeJwt == null) {
            return new EffectiveSecurityConfig.Jwt(
                    globalJwt.getIssuer(),
                    globalJwt.getAudience(),
                    globalJwt.getPublicKey(),
                    globalJwt.getJwksUrl(),
                    globalJwt.getJwksRefreshSeconds(),
                    globalJwt.getJwksConnectTimeoutMillis(),
                    globalJwt.getJwksReadTimeoutMillis());
        }
        return new EffectiveSecurityConfig.Jwt(
                choose(routeJwt.getIssuer(), globalJwt.getIssuer()),
                choose(routeJwt.getAudience(), globalJwt.getAudience()),
                choose(routeJwt.getPublicKey(), globalJwt.getPublicKey()),
                choose(routeJwt.getJwksUrl(), globalJwt.getJwksUrl()),
                choose(routeJwt.getJwksRefreshSeconds(),
                        globalJwt.getJwksRefreshSeconds()),
                choose(routeJwt.getJwksConnectTimeoutMillis(),
                        globalJwt.getJwksConnectTimeoutMillis()),
                choose(routeJwt.getJwksReadTimeoutMillis(),
                        globalJwt.getJwksReadTimeoutMillis()));
    }

    private static EffectiveSecurityConfig.RateLimit mergeRateLimit(
            SecurityProperties.RateLimitProperties globalRateLimit,
            RouteSecurityProperties.RouteRateLimitProperties routeRateLimit) {
        return new EffectiveSecurityConfig.RateLimit(
                mergeIpRateLimit(
                        globalRateLimit.getIp(),
                        routeRateLimit == null ? null : routeRateLimit.getIp()),
                mergeUserRateLimit(
                        globalRateLimit.getUser(),
                        routeRateLimit == null ? null : routeRateLimit.getUser()));
    }

    private static EffectiveSecurityConfig.IpRateLimit mergeIpRateLimit(
            SecurityProperties.IpRateLimitProperties globalIpRateLimit,
            RouteSecurityProperties.RouteIpRateLimitProperties routeIpRateLimit) {
        if (routeIpRateLimit == null) {
            return new EffectiveSecurityConfig.IpRateLimit(
                    globalIpRateLimit.isEnabled(),
                    globalIpRateLimit.isShadow(),
                    globalIpRateLimit.getMode(),
                    globalIpRateLimit.getPermitsPerSecond(),
                    globalIpRateLimit.getBurstCapacity(),
                    globalIpRateLimit.getDistributedTimeoutMillis());
        }
        return new EffectiveSecurityConfig.IpRateLimit(
                choose(routeIpRateLimit.getEnabled(), globalIpRateLimit.isEnabled()),
                choose(routeIpRateLimit.getShadow(), globalIpRateLimit.isShadow()),
                choose(routeIpRateLimit.getMode(), globalIpRateLimit.getMode()),
                choose(routeIpRateLimit.getPermitsPerSecond(),
                        globalIpRateLimit.getPermitsPerSecond()),
                choose(routeIpRateLimit.getBurstCapacity(),
                        globalIpRateLimit.getBurstCapacity()),
                choose(routeIpRateLimit.getDistributedTimeoutMillis(),
                        globalIpRateLimit.getDistributedTimeoutMillis()));
    }

    private static EffectiveSecurityConfig.UserRateLimit mergeUserRateLimit(
            SecurityProperties.UserRateLimitProperties globalUserRateLimit,
            RouteSecurityProperties.RouteUserRateLimitProperties routeUserRateLimit) {
        if (routeUserRateLimit == null) {
            return new EffectiveSecurityConfig.UserRateLimit(
                    globalUserRateLimit.isEnabled(),
                    globalUserRateLimit.isShadow(),
                    globalUserRateLimit.getMode(),
                    globalUserRateLimit.getPermitsPerSecond(),
                    globalUserRateLimit.getBurstCapacity(),
                    globalUserRateLimit.getDistributedTimeoutMillis());
        }
        return new EffectiveSecurityConfig.UserRateLimit(
                choose(routeUserRateLimit.getEnabled(), globalUserRateLimit.isEnabled()),
                choose(routeUserRateLimit.getShadow(), globalUserRateLimit.isShadow()),
                choose(routeUserRateLimit.getMode(), globalUserRateLimit.getMode()),
                choose(routeUserRateLimit.getPermitsPerSecond(),
                        globalUserRateLimit.getPermitsPerSecond()),
                choose(routeUserRateLimit.getBurstCapacity(),
                        globalUserRateLimit.getBurstCapacity()),
                choose(routeUserRateLimit.getDistributedTimeoutMillis(),
                        globalUserRateLimit.getDistributedTimeoutMillis()));
    }

    private static <T> T choose(T routeValue, T globalValue) {
        return routeValue == null ? globalValue : routeValue;
    }

    private static <T> List<T> chooseList(List<T> routeValue,
            List<T> globalValue) {
        return routeValue == null ? List.copyOf(globalValue)
                : List.copyOf(routeValue);
    }
}
