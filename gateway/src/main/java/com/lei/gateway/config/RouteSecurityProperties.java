package com.lei.gateway.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import java.util.ArrayList;
import java.util.List;

/**
 * 路由级安全覆盖配置。
 *
 * <p>该配置仅作为覆盖层，字段允许为空。实际生效值由全局配置与路由覆盖共同计算。
 */
public class RouteSecurityProperties {

    /**
     * 路由级与全局配置的合并模式。
     */
    private SecurityProperties.MergeMode mode = SecurityProperties.MergeMode.MERGE;

    /**
     * 是否启用安全过滤链（null 表示继承全局）。
     */
    private Boolean enabled;

    /**
     * 路由级可信代理地址列表覆盖（null 表示继承全局）。
     */
    private List<String> trustedProxies;

    /**
     * 路由级固定可信代理跳数覆盖（null 表示继承全局）。
     */
    @Min(0)
    private Integer trustedProxyHops;

    /**
     * 路由级 IP 访问控制覆盖配置。
     */
    @Valid
    private RouteIpAccessProperties ipAccess;

    /**
     * 路由级认证覆盖配置。
     */
    @Valid
    private RouteAuthProperties auth;

    /**
     * 路由级限流覆盖配置。
     */
    @Valid
    private RouteRateLimitProperties rateLimit;

    public SecurityProperties.MergeMode getMode() {
        return mode;
    }

    public void setMode(SecurityProperties.MergeMode mode) {
        this.mode = mode == null ? SecurityProperties.MergeMode.MERGE : mode;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    public List<String> getTrustedProxies() {
        return trustedProxies == null ? null : new ArrayList<>(trustedProxies);
    }

    public void setTrustedProxies(List<String> trustedProxies) {
        this.trustedProxies = trustedProxies == null
                ? null : new ArrayList<>(trustedProxies);
    }

    public Integer getTrustedProxyHops() {
        return trustedProxyHops;
    }

    public void setTrustedProxyHops(Integer trustedProxyHops) {
        this.trustedProxyHops = trustedProxyHops;
    }

    public RouteIpAccessProperties getIpAccess() {
        return ipAccess;
    }

    public void setIpAccess(RouteIpAccessProperties ipAccess) {
        this.ipAccess = ipAccess;
    }

    public RouteAuthProperties getAuth() {
        return auth;
    }

    public void setAuth(RouteAuthProperties auth) {
        this.auth = auth;
    }

    public RouteRateLimitProperties getRateLimit() {
        return rateLimit;
    }

    public void setRateLimit(RouteRateLimitProperties rateLimit) {
        this.rateLimit = rateLimit;
    }

    /**
     * 路由级 IP 访问控制覆盖。
     */
    public static class RouteIpAccessProperties {

        /** 是否启用 IP 黑白名单过滤（null 表示继承全局）。 */
        private Boolean enabled;
        /** 是否启用影子模式（null 表示继承全局）。 */
        private Boolean shadow;
        /** 失败策略覆盖：true=失败关闭，false=失败打开。 */
        private Boolean failClosed;
        /** 白名单规则覆盖（null 表示继承全局）。 */
        private List<String> allowList;
        /** 黑名单规则覆盖（null 表示继承全局）。 */
        private List<String> denyList;

        public Boolean getEnabled() {
            return enabled;
        }

        public void setEnabled(Boolean enabled) {
            this.enabled = enabled;
        }

        public Boolean getShadow() {
            return shadow;
        }

        public void setShadow(Boolean shadow) {
            this.shadow = shadow;
        }

        public Boolean getFailClosed() {
            return failClosed;
        }

        public void setFailClosed(Boolean failClosed) {
            this.failClosed = failClosed;
        }

        public List<String> getAllowList() {
            return allowList == null ? null : new ArrayList<>(allowList);
        }

        public void setAllowList(List<String> allowList) {
            this.allowList = allowList == null
                    ? null : new ArrayList<>(allowList);
        }

        public List<String> getDenyList() {
            return denyList == null ? null : new ArrayList<>(denyList);
        }

        public void setDenyList(List<String> denyList) {
            this.denyList = denyList == null ? null : new ArrayList<>(denyList);
        }
    }

    /**
     * 路由级认证覆盖。
     */
    public static class RouteAuthProperties {

        /** 是否启用认证过滤（null 表示继承全局）。 */
        private Boolean enabled;
        /** 认证过滤是否影子模式（null 表示继承全局）。 */
        private Boolean shadow;
        /** 失败策略覆盖：true=失败关闭，false=失败打开。 */
        private Boolean failClosed;
        /** 认证类型覆盖。 */
        private SecurityProperties.AuthType type;

        /** Token 提取覆盖配置。 */
        @Valid
        private RouteTokenExtractorProperties tokenExtractor;

        /** 认证提供器覆盖配置。 */
        @Valid
        private RouteAuthProvidersProperties providers;

        public Boolean getEnabled() {
            return enabled;
        }

        public void setEnabled(Boolean enabled) {
            this.enabled = enabled;
        }

        public Boolean getShadow() {
            return shadow;
        }

        public void setShadow(Boolean shadow) {
            this.shadow = shadow;
        }

        public Boolean getFailClosed() {
            return failClosed;
        }

        public void setFailClosed(Boolean failClosed) {
            this.failClosed = failClosed;
        }

        public SecurityProperties.AuthType getType() {
            return type;
        }

        public void setType(SecurityProperties.AuthType type) {
            this.type = type;
        }

        public RouteTokenExtractorProperties getTokenExtractor() {
            return tokenExtractor;
        }

        public void setTokenExtractor(RouteTokenExtractorProperties tokenExtractor) {
            this.tokenExtractor = tokenExtractor;
        }

        public RouteAuthProvidersProperties getProviders() {
            return providers;
        }

        public void setProviders(RouteAuthProvidersProperties providers) {
            this.providers = providers;
        }
    }

    /**
     * 路由级 Token 提取配置覆盖。
     */
    public static class RouteTokenExtractorProperties {

        /** 认证 Token 请求头名称覆盖。 */
        private String tokenHeaderName;

        /** 认证 Token 值前缀覆盖。 */
        private String tokenValuePrefix;

        public String getTokenHeaderName() {
            return tokenHeaderName;
        }

        public void setTokenHeaderName(String tokenHeaderName) {
            this.tokenHeaderName = tokenHeaderName;
        }

        public String getTokenValuePrefix() {
            return tokenValuePrefix;
        }

        public void setTokenValuePrefix(String tokenValuePrefix) {
            this.tokenValuePrefix = tokenValuePrefix;
        }
    }

    /**
     * 路由级认证提供器配置覆盖。
     */
    public static class RouteAuthProvidersProperties {

        /** JWT 提供器覆盖配置。 */
        @Valid
        private RouteJwtProperties jwt;

        public RouteJwtProperties getJwt() {
            return jwt;
        }

        public void setJwt(RouteJwtProperties jwt) {
            this.jwt = jwt;
        }
    }

    /**
     * 路由级 JWT 配置覆盖。
     */
    public static class RouteJwtProperties {

        /** 期望签发者（iss）覆盖。 */
        private String issuer;
        /** 期望受众（aud）覆盖。 */
        private String audience;
        /** 静态公钥覆盖。 */
        private String publicKey;
        /** JWKS 地址覆盖。 */
        private String jwksUrl;
        /** JWKS 刷新间隔覆盖（秒）。 */
        private Integer jwksRefreshSeconds;
        /** JWKS 连接超时覆盖（毫秒）。 */
        private Integer jwksConnectTimeoutMillis;
        /** JWKS 读取超时覆盖（毫秒）。 */
        private Integer jwksReadTimeoutMillis;

        public String getIssuer() {
            return issuer;
        }

        public void setIssuer(String issuer) {
            this.issuer = issuer;
        }

        public String getAudience() {
            return audience;
        }

        public void setAudience(String audience) {
            this.audience = audience;
        }

        public String getPublicKey() {
            return publicKey;
        }

        public void setPublicKey(String publicKey) {
            this.publicKey = publicKey;
        }

        public String getJwksUrl() {
            return jwksUrl;
        }

        public void setJwksUrl(String jwksUrl) {
            this.jwksUrl = jwksUrl;
        }

        public Integer getJwksRefreshSeconds() {
            return jwksRefreshSeconds;
        }

        public void setJwksRefreshSeconds(Integer jwksRefreshSeconds) {
            this.jwksRefreshSeconds = jwksRefreshSeconds;
        }

        public Integer getJwksConnectTimeoutMillis() {
            return jwksConnectTimeoutMillis;
        }

        public void setJwksConnectTimeoutMillis(Integer jwksConnectTimeoutMillis) {
            this.jwksConnectTimeoutMillis = jwksConnectTimeoutMillis;
        }

        public Integer getJwksReadTimeoutMillis() {
            return jwksReadTimeoutMillis;
        }

        public void setJwksReadTimeoutMillis(Integer jwksReadTimeoutMillis) {
            this.jwksReadTimeoutMillis = jwksReadTimeoutMillis;
        }
    }

    /**
     * 路由级限流覆盖。
     */
    public static class RouteRateLimitProperties {

        /** 路由级 IP 限流覆盖。 */
        @Valid
        private RouteIpRateLimitProperties ip;

        /** 路由级用户限流覆盖。 */
        @Valid
        private RouteUserRateLimitProperties user;

        public RouteIpRateLimitProperties getIp() {
            return ip;
        }

        public void setIp(RouteIpRateLimitProperties ip) {
            this.ip = ip;
        }

        public RouteUserRateLimitProperties getUser() {
            return user;
        }

        public void setUser(RouteUserRateLimitProperties user) {
            this.user = user;
        }
    }

    /**
     * 路由级限流公共覆盖。
     */
    public abstract static class BaseRouteRateLimitProperties {

        /** 限流器开关覆盖（null 表示继承全局）。 */
        private Boolean enabled;
        /** 影子模式覆盖（null 表示继承全局）。 */
        private Boolean shadow;
        /** 限流模式覆盖（null 表示继承全局）。 */
        private SecurityProperties.RateLimitMode mode;
        /** 每秒令牌数覆盖。 */
        private Integer permitsPerSecond;
        /** 桶容量覆盖。 */
        private Integer burstCapacity;
        /** 分布式限流超时覆盖（毫秒）。 */
        private Integer distributedTimeoutMillis;

        public Boolean getEnabled() {
            return enabled;
        }

        public void setEnabled(Boolean enabled) {
            this.enabled = enabled;
        }

        public Boolean getShadow() {
            return shadow;
        }

        public void setShadow(Boolean shadow) {
            this.shadow = shadow;
        }

        public SecurityProperties.RateLimitMode getMode() {
            return mode;
        }

        public void setMode(SecurityProperties.RateLimitMode mode) {
            this.mode = mode;
        }

        public Integer getPermitsPerSecond() {
            return permitsPerSecond;
        }

        public void setPermitsPerSecond(Integer permitsPerSecond) {
            this.permitsPerSecond = permitsPerSecond;
        }

        public Integer getBurstCapacity() {
            return burstCapacity;
        }

        public void setBurstCapacity(Integer burstCapacity) {
            this.burstCapacity = burstCapacity;
        }

        public Integer getDistributedTimeoutMillis() {
            return distributedTimeoutMillis;
        }

        public void setDistributedTimeoutMillis(Integer distributedTimeoutMillis) {
            this.distributedTimeoutMillis = distributedTimeoutMillis;
        }
    }

    /**
     * 路由级 IP 限流覆盖。
     */
    public static class RouteIpRateLimitProperties extends BaseRouteRateLimitProperties {
    }

    /**
     * 路由级用户限流覆盖。
     */
    public static class RouteUserRateLimitProperties extends BaseRouteRateLimitProperties {
    }
}
