package com.lei.gateway.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 网关安全配置，绑定 {@code gateway.security} 前缀。
 */
@Validated
@ConfigurationProperties(prefix = "gateway.security")
public class SecurityProperties {

    /**
     * 全局安全过滤器开关。
     */
    private boolean enabled;

    /**
     * 可信代理地址列表（支持单 IP / CIDR）。
     */
    private List<String> trustedProxies = new ArrayList<>();

    /**
     * 固定可信代理跳数（从 XFF 右向左计数）。未配置时按 trustedProxies 模式解析。
     */
    @Min(0)
    private Integer trustedProxyHops;

    /**
     * IP 访问控制配置。
     */
    @Valid
    private IpAccessProperties ipAccess = new IpAccessProperties();

    /**
     * 认证配置。
     */
    @Valid
    private AuthProperties auth = new AuthProperties();

    /**
     * 限流配置。
     */
    @Valid
    private RateLimitProperties rateLimit = new RateLimitProperties();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public List<String> getTrustedProxies() {
        return new ArrayList<>(trustedProxies);
    }

    public void setTrustedProxies(List<String> trustedProxies) {
        this.trustedProxies = trustedProxies == null
                ? new ArrayList<>() : new ArrayList<>(trustedProxies);
    }

    public Integer getTrustedProxyHops() {
        return trustedProxyHops;
    }

    public void setTrustedProxyHops(Integer trustedProxyHops) {
        this.trustedProxyHops = trustedProxyHops;
    }

    public IpAccessProperties getIpAccess() {
        return ipAccess;
    }

    public void setIpAccess(IpAccessProperties ipAccess) {
        this.ipAccess = ipAccess == null ? new IpAccessProperties() : ipAccess;
    }

    public AuthProperties getAuth() {
        return auth;
    }

    public void setAuth(AuthProperties auth) {
        this.auth = auth == null ? new AuthProperties() : auth;
    }

    public RateLimitProperties getRateLimit() {
        return rateLimit;
    }

    public void setRateLimit(RateLimitProperties rateLimit) {
        this.rateLimit = rateLimit == null ? new RateLimitProperties() : rateLimit;
    }

    /**
     * 路由策略组合模式。
     */
    public enum MergeMode {
        MERGE,
        REPLACE
    }

    /**
     * 限流引擎模式。
     */
    public enum RateLimitMode {
        LOCAL,
        DISTRIBUTED
    }

    /**
     * 认证类型。
     */
    public enum AuthType {
        JWT
    }

    /**
     * IP 访问控制配置。
     */
    public static class IpAccessProperties {

        /** 是否启用 IP 黑白名单过滤。 */
        private boolean enabled;
        /** 影子模式：命中规则只记录不拦截。 */
        private boolean shadow;
        /** 失败策略：true=失败关闭（拒绝请求）。 */
        private boolean failClosed = true;
        /** 白名单规则（单 IP/CIDR）。 */
        private List<String> allowList = new ArrayList<>();
        /** 黑名单规则（单 IP/CIDR，优先级高于白名单）。 */
        private List<String> denyList = new ArrayList<>();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public boolean isShadow() {
            return shadow;
        }

        public void setShadow(boolean shadow) {
            this.shadow = shadow;
        }

        public boolean isFailClosed() {
            return failClosed;
        }

        public void setFailClosed(boolean failClosed) {
            this.failClosed = failClosed;
        }

        public List<String> getAllowList() {
            return new ArrayList<>(allowList);
        }

        public void setAllowList(List<String> allowList) {
            this.allowList = allowList == null
                    ? new ArrayList<>() : new ArrayList<>(allowList);
        }

        public List<String> getDenyList() {
            return new ArrayList<>(denyList);
        }

        public void setDenyList(List<String> denyList) {
            this.denyList = denyList == null
                    ? new ArrayList<>() : new ArrayList<>(denyList);
        }
    }

    /**
     * 认证配置。
     */
    public static class AuthProperties {

        /** 是否启用认证过滤。 */
        private boolean enabled;
        /** 影子模式：认证失败仅记录不拦截。 */
        private boolean shadow;
        /** 失败策略：true=失败关闭（拒绝请求）。 */
        private boolean failClosed = true;
        /** 认证类型。 */
        private AuthType type = AuthType.JWT;

        /** Token 提取配置。 */
        @Valid
        private TokenExtractorProperties tokenExtractor =
                new TokenExtractorProperties();

        /** 认证提供器配置。 */
        @Valid
        private AuthProvidersProperties providers =
                new AuthProvidersProperties();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public boolean isShadow() {
            return shadow;
        }

        public void setShadow(boolean shadow) {
            this.shadow = shadow;
        }

        public boolean isFailClosed() {
            return failClosed;
        }

        public void setFailClosed(boolean failClosed) {
            this.failClosed = failClosed;
        }

        public AuthType getType() {
            return type;
        }

        public void setType(AuthType type) {
            this.type = type == null ? AuthType.JWT : type;
        }

        public TokenExtractorProperties getTokenExtractor() {
            return tokenExtractor;
        }

        public void setTokenExtractor(TokenExtractorProperties tokenExtractor) {
            this.tokenExtractor = tokenExtractor == null
                    ? new TokenExtractorProperties() : tokenExtractor;
        }

        public AuthProvidersProperties getProviders() {
            return providers;
        }

        public void setProviders(AuthProvidersProperties providers) {
            this.providers = providers == null
                    ? new AuthProvidersProperties() : providers;
        }
    }

    /**
     * Token 提取配置。
     */
    public static class TokenExtractorProperties {

        /** 客户端传递认证 Token 的请求头名称。 */
        private String tokenHeaderName = "Authorization";

        /** 认证 Token 值前缀。 */
        private String tokenValuePrefix = "Bearer ";

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
     * 认证提供器配置。
     */
    public static class AuthProvidersProperties {

        /** JWT 提供器配置。 */
        @Valid
        private JwtProperties jwt = new JwtProperties();

        public JwtProperties getJwt() {
            return jwt;
        }

        public void setJwt(JwtProperties jwt) {
            this.jwt = jwt == null ? new JwtProperties() : jwt;
        }
    }

    /**
     * JWT 配置。
     */
    public static class JwtProperties {

        /** 期望的发行方（iss）。 */
        private String issuer;
        /** 期望的受众（aud）。 */
        private String audience;
        /** 静态公钥（PEM）。 */
        private String publicKey;
        /** JWKS 地址。 */
        private String jwksUrl;

        /** JWKS 刷新间隔（秒）。 */
        @Min(1)
        private int jwksRefreshSeconds = 300;

        /** JWKS 拉取连接超时（毫秒）。 */
        @Min(1)
        private int jwksConnectTimeoutMillis = 500;

        /** JWKS 拉取读取超时（毫秒）。 */
        @Min(1)
        private int jwksReadTimeoutMillis = 1000;

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

        public int getJwksRefreshSeconds() {
            return jwksRefreshSeconds;
        }

        public void setJwksRefreshSeconds(int jwksRefreshSeconds) {
            this.jwksRefreshSeconds = jwksRefreshSeconds;
        }

        public int getJwksConnectTimeoutMillis() {
            return jwksConnectTimeoutMillis;
        }

        public void setJwksConnectTimeoutMillis(int jwksConnectTimeoutMillis) {
            this.jwksConnectTimeoutMillis = jwksConnectTimeoutMillis;
        }

        public int getJwksReadTimeoutMillis() {
            return jwksReadTimeoutMillis;
        }

        public void setJwksReadTimeoutMillis(int jwksReadTimeoutMillis) {
            this.jwksReadTimeoutMillis = jwksReadTimeoutMillis;
        }
    }

    /**
     * 限流配置。
     */
    public static class RateLimitProperties {

        /** 按客户端 IP 的限流配置。 */
        @Valid
        private IpRateLimitProperties ip = new IpRateLimitProperties();

        /** 按 userId 的限流配置。 */
        @Valid
        private UserRateLimitProperties user = new UserRateLimitProperties();

        public IpRateLimitProperties getIp() {
            return ip;
        }

        public void setIp(IpRateLimitProperties ip) {
            this.ip = ip == null ? new IpRateLimitProperties() : ip;
        }

        public UserRateLimitProperties getUser() {
            return user;
        }

        public void setUser(UserRateLimitProperties user) {
            this.user = user == null ? new UserRateLimitProperties() : user;
        }
    }

    /**
     * 限流公共配置。
     */
    public abstract static class BaseRateLimitProperties {

        /** 是否启用当前限流器。 */
        private boolean enabled;
        /** 影子模式：超限仅记录不拦截。 */
        private boolean shadow;
        /** 限流模式：本地或分布式。 */
        private RateLimitMode mode = RateLimitMode.LOCAL;

        /** 每秒令牌数。 */
        @Min(1)
        private int permitsPerSecond = 100;

        /** 突发容量。 */
        @Min(1)
        private int burstCapacity = 200;

        /** 分布式限流超时时间（毫秒）。 */
        @Min(1)
        private int distributedTimeoutMillis = 50;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public boolean isShadow() {
            return shadow;
        }

        public void setShadow(boolean shadow) {
            this.shadow = shadow;
        }

        public RateLimitMode getMode() {
            return mode;
        }

        public void setMode(RateLimitMode mode) {
            this.mode = mode == null ? RateLimitMode.LOCAL : mode;
        }

        public int getPermitsPerSecond() {
            return permitsPerSecond;
        }

        public void setPermitsPerSecond(int permitsPerSecond) {
            this.permitsPerSecond = permitsPerSecond;
        }

        public int getBurstCapacity() {
            return burstCapacity;
        }

        public void setBurstCapacity(int burstCapacity) {
            this.burstCapacity = burstCapacity;
        }

        public int getDistributedTimeoutMillis() {
            return distributedTimeoutMillis;
        }

        public void setDistributedTimeoutMillis(int distributedTimeoutMillis) {
            this.distributedTimeoutMillis = distributedTimeoutMillis;
        }
    }

    /**
     * 按客户端 IP 的限流配置。
     */
    public static class IpRateLimitProperties extends BaseRateLimitProperties {
    }

    /**
     * 按 userId 的限流配置。
     */
    public static class UserRateLimitProperties extends BaseRateLimitProperties {
    }
}
