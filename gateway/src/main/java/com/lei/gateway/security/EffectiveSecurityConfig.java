package com.lei.gateway.security;

import com.lei.gateway.config.SecurityProperties;
import java.util.ArrayList;
import java.util.List;

/**
 * 请求生效后的安全配置快照。
 */
public class EffectiveSecurityConfig {

    /**
     * 当前请求是否启用安全过滤链。
     */
    private final boolean enabled;

    /**
     * 可信代理地址列表（单 IP / CIDR）。
     */
    private final List<String> trustedProxies;

    /**
     * 固定可信代理跳数（从 XFF 右向左计数）。
     */
    private final Integer trustedProxyHops;

    /**
     * IP 访问控制生效配置。
     */
    private final IpAccess ipAccess;

    /**
     * 认证生效配置。
     */
    private final Auth auth;

    /**
     * 限流生效配置。
     */
    private final RateLimit rateLimit;

    /**
     * 创建生效安全配置。
     */
    public EffectiveSecurityConfig(boolean enabled,
            List<String> trustedProxies,
            Integer trustedProxyHops,
            IpAccess ipAccess,
            Auth auth,
            RateLimit rateLimit) {
        this.enabled = enabled;
        this.trustedProxies = trustedProxies == null
                ? List.of() : List.copyOf(trustedProxies);
        this.trustedProxyHops = trustedProxyHops;
        this.ipAccess = ipAccess;
        this.auth = auth;
        this.rateLimit = rateLimit;
    }

    /**
     * 返回当前请求是否启用安全过滤链。
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 返回可信代理地址列表副本。
     */
    public List<String> getTrustedProxies() {
        return new ArrayList<>(trustedProxies);
    }

    /**
     * 返回固定可信代理跳数。
     */
    public Integer getTrustedProxyHops() {
        return trustedProxyHops;
    }

    /**
     * 返回 IP 访问控制生效配置。
     */
    public IpAccess getIpAccess() {
        return ipAccess;
    }

    /**
     * 返回认证生效配置。
     */
    public Auth getAuth() {
        return auth;
    }

    /**
     * 返回限流生效配置。
     */
    public RateLimit getRateLimit() {
        return rateLimit;
    }

    /**
     * IP 访问控制生效配置。
     */
    public static class IpAccess {

        /**
         * 是否启用 IP 黑白名单过滤。
         */
        private final boolean enabled;

        /**
         * 是否启用影子模式（只记录不阻断）。
         */
        private final boolean shadow;

        /**
         * 失败策略：true 表示失败关闭（拒绝请求）。
         */
        private final boolean failClosed;

        /**
         * 白名单规则列表（单 IP / CIDR）。
         */
        private final List<String> allowList;

        /**
         * 黑名单规则列表（单 IP / CIDR，优先级高于白名单）。
         */
        private final List<String> denyList;

        /**
         * 创建 IP 访问控制配置。
         */
        public IpAccess(boolean enabled, boolean shadow,
                boolean failClosed, List<String> allowList,
                List<String> denyList) {
            this.enabled = enabled;
            this.shadow = shadow;
            this.failClosed = failClosed;
            this.allowList = allowList == null
                    ? List.of() : List.copyOf(allowList);
            this.denyList = denyList == null
                    ? List.of() : List.copyOf(denyList);
        }

        /**
         * 返回 IP 访问控制是否启用。
         */
        public boolean isEnabled() {
            return enabled;
        }

        /**
         * 返回是否启用影子模式。
         */
        public boolean isShadow() {
            return shadow;
        }

        /**
         * 返回失败策略是否为失败关闭。
         */
        public boolean isFailClosed() {
            return failClosed;
        }

        /**
         * 返回白名单规则列表副本。
         */
        public List<String> getAllowList() {
            return new ArrayList<>(allowList);
        }

        /**
         * 返回黑名单规则列表副本。
         */
        public List<String> getDenyList() {
            return new ArrayList<>(denyList);
        }
    }

    /**
     * 认证生效配置。
     */
    public static class Auth {

        /**
         * 是否启用认证过滤。
         */
        private final boolean enabled;

        /**
         * 是否启用影子模式（认证失败只记录不拦截）。
         */
        private final boolean shadow;

        /**
         * 失败策略：true 表示失败关闭（拒绝请求）。
         */
        private final boolean failClosed;

        /**
         * 认证类型。
         */
        private final SecurityProperties.AuthType type;

        /**
         * Token 提取生效配置。
         */
        private final TokenExtractor tokenExtractor;

        /**
         * 认证提供器生效配置。
         */
        private final Providers providers;

        /**
         * 创建认证配置。
         */
        public Auth(boolean enabled, boolean shadow, boolean failClosed,
                SecurityProperties.AuthType type,
                TokenExtractor tokenExtractor,
                Providers providers) {
            this.enabled = enabled;
            this.shadow = shadow;
            this.failClosed = failClosed;
            this.type = type;
            this.tokenExtractor = tokenExtractor;
            this.providers = providers;
        }

        /**
         * 返回认证过滤是否启用。
         */
        public boolean isEnabled() {
            return enabled;
        }

        /**
         * 返回认证过滤是否为影子模式。
         */
        public boolean isShadow() {
            return shadow;
        }

        /**
         * 返回失败策略是否为失败关闭。
         */
        public boolean isFailClosed() {
            return failClosed;
        }

        /**
         * 返回认证类型。
         */
        public SecurityProperties.AuthType getType() {
            return type;
        }

        /**
         * 返回 Token 提取生效配置。
         */
        public TokenExtractor getTokenExtractor() {
            return tokenExtractor;
        }

        /**
         * 返回认证提供器生效配置。
         */
        public Providers getProviders() {
            return providers;
        }
    }

    /**
     * Token 提取生效配置。
     */
    public static class TokenExtractor {

        /**
         * 客户端认证 Token 请求头名称。
         */
        private final String tokenHeaderName;

        /**
         * 认证 Token 值前缀。
         */
        private final String tokenValuePrefix;

        /**
         * 创建 Token 提取配置。
         */
        public TokenExtractor(String tokenHeaderName, String tokenValuePrefix) {
            this.tokenHeaderName = tokenHeaderName;
            this.tokenValuePrefix = tokenValuePrefix;
        }

        /**
         * 返回客户端认证 Token 请求头名称。
         */
        public String getTokenHeaderName() {
            return tokenHeaderName;
        }

        /**
         * 返回认证 Token 值前缀。
         */
        public String getTokenValuePrefix() {
            return tokenValuePrefix;
        }
    }

    /**
     * 认证提供器生效配置。
     */
    public static class Providers {

        /**
         * JWT 提供器生效配置。
         */
        private final Jwt jwt;

        /**
         * 创建认证提供器配置。
         */
        public Providers(Jwt jwt) {
            this.jwt = jwt;
        }

        /**
         * 返回 JWT 提供器生效配置。
         */
        public Jwt getJwt() {
            return jwt;
        }
    }

    /**
     * JWT 提供器生效配置。
     */
    public static class Jwt {

        /**
         * 期望的签发者（iss）。
         */
        private final String issuer;

        /**
         * 期望的受众（aud）。
         */
        private final String audience;

        /**
         * 直接配置的公钥内容。
         */
        private final String publicKey;

        /**
         * JWKS 地址。
         */
        private final String jwksUrl;

        /**
         * JWKS 刷新间隔（秒）。
         */
        private final int jwksRefreshSeconds;

        /**
         * JWKS 连接超时（毫秒）。
         */
        private final int jwksConnectTimeoutMillis;

        /**
         * JWKS 读取超时（毫秒）。
         */
        private final int jwksReadTimeoutMillis;

        /**
         * 创建 JWT 配置。
         */
        public Jwt(String issuer, String audience, String publicKey,
                String jwksUrl, int jwksRefreshSeconds,
                int jwksConnectTimeoutMillis, int jwksReadTimeoutMillis) {
            this.issuer = issuer;
            this.audience = audience;
            this.publicKey = publicKey;
            this.jwksUrl = jwksUrl;
            this.jwksRefreshSeconds = jwksRefreshSeconds;
            this.jwksConnectTimeoutMillis = jwksConnectTimeoutMillis;
            this.jwksReadTimeoutMillis = jwksReadTimeoutMillis;
        }

        /**
         * 返回期望签发者（iss）。
         */
        public String getIssuer() {
            return issuer;
        }

        /**
         * 返回期望受众（aud）。
         */
        public String getAudience() {
            return audience;
        }

        /**
         * 返回直接配置公钥。
         */
        public String getPublicKey() {
            return publicKey;
        }

        /**
         * 返回 JWKS 地址。
         */
        public String getJwksUrl() {
            return jwksUrl;
        }

        /**
         * 返回 JWKS 刷新间隔（秒）。
         */
        public int getJwksRefreshSeconds() {
            return jwksRefreshSeconds;
        }

        /**
         * 返回 JWKS 连接超时（毫秒）。
         */
        public int getJwksConnectTimeoutMillis() {
            return jwksConnectTimeoutMillis;
        }

        /**
         * 返回 JWKS 读取超时（毫秒）。
         */
        public int getJwksReadTimeoutMillis() {
            return jwksReadTimeoutMillis;
        }
    }

    /**
     * 限流生效配置。
     */
    public static class RateLimit {

        /**
         * 按客户端 IP 的限流生效配置。
         */
        private final IpRateLimit ip;

        /**
         * 按 userId 的限流生效配置。
         */
        private final UserRateLimit user;

        /**
         * 创建限流配置。
         */
        public RateLimit(IpRateLimit ip, UserRateLimit user) {
            this.ip = ip;
            this.user = user;
        }

        /**
         * 返回按客户端 IP 的限流配置。
         */
        public IpRateLimit getIp() {
            return ip;
        }

        /**
         * 返回按 userId 的限流配置。
         */
        public UserRateLimit getUser() {
            return user;
        }
    }

    /**
     * 限流规则生效配置。
     */
    public abstract static class BaseRateLimit {

        /**
         * 是否启用当前限流规则。
         */
        private final boolean enabled;

        /**
         * 是否启用影子模式（只记录不阻断）。
         */
        private final boolean shadow;

        /**
         * 限流实现模式（本地或分布式）。
         */
        private final SecurityProperties.RateLimitMode mode;

        /**
         * 每秒令牌发放速率。
         */
        private final int permitsPerSecond;

        /**
         * 令牌桶容量上限。
         */
        private final int burstCapacity;

        /**
         * 分布式限流模式下获取令牌的超时时间（毫秒）。
         */
        private final int distributedTimeoutMillis;

        /**
         * 创建限流规则配置。
         */
        protected BaseRateLimit(boolean enabled, boolean shadow,
                SecurityProperties.RateLimitMode mode,
                int permitsPerSecond, int burstCapacity,
                int distributedTimeoutMillis) {
            this.enabled = enabled;
            this.shadow = shadow;
            this.mode = mode;
            this.permitsPerSecond = permitsPerSecond;
            this.burstCapacity = burstCapacity;
            this.distributedTimeoutMillis = distributedTimeoutMillis;
        }

        /**
         * 返回限流规则是否启用。
         */
        public boolean isEnabled() {
            return enabled;
        }

        /**
         * 返回是否为影子模式（只记录不阻断）。
         */
        public boolean isShadow() {
            return shadow;
        }

        /**
         * 返回限流实现模式。
         */
        public SecurityProperties.RateLimitMode getMode() {
            return mode;
        }

        /**
         * 返回每秒令牌发放速率。
         */
        public int getPermitsPerSecond() {
            return permitsPerSecond;
        }

        /**
         * 返回令牌桶容量上限。
         */
        public int getBurstCapacity() {
            return burstCapacity;
        }

        /**
         * 返回分布式限流模式下获取令牌超时时间（毫秒）。
         */
        public int getDistributedTimeoutMillis() {
            return distributedTimeoutMillis;
        }
    }

    /**
     * 按客户端 IP 的限流规则。
     */
    public static class IpRateLimit extends BaseRateLimit {

        /**
         * 创建 IP 限流规则。
         */
        public IpRateLimit(boolean enabled, boolean shadow,
                SecurityProperties.RateLimitMode mode,
                int permitsPerSecond, int burstCapacity,
                int distributedTimeoutMillis) {
            super(enabled, shadow, mode, permitsPerSecond,
                    burstCapacity, distributedTimeoutMillis);
        }
    }

    /**
     * 按 userId 的限流规则。
     */
    public static class UserRateLimit extends BaseRateLimit {

        /**
         * 创建用户限流规则。
         */
        public UserRateLimit(boolean enabled, boolean shadow,
                SecurityProperties.RateLimitMode mode,
                int permitsPerSecond, int burstCapacity,
                int distributedTimeoutMillis) {
            super(enabled, shadow, mode, permitsPerSecond,
                    burstCapacity, distributedTimeoutMillis);
        }
    }
}
