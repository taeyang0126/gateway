package com.lei.gateway.plugin;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.lei.gateway.config.SecurityProperties;
import com.lei.gateway.security.AuthProvider;
import com.lei.gateway.security.AuthenticationResult;
import com.lei.gateway.security.EffectiveSecurityConfig;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JWT 认证插件。
 *
 * <p>验证 JWT token，成功写入 PluginContext.userId 和 attributes(authRequired/authPassed)，
 * 失败返回 SHORT_CIRCUIT(401)。shadow 模式下失败只记日志不拦截。
 */
public class AuthPlugin implements Plugin {

    private static final Logger log = LoggerFactory.getLogger(AuthPlugin.class);
    private static final String NAME = "auth";
    private static final int DEFAULT_PRIORITY = 4000;
    private static final String USER_ID_HEADER = "x-userId";

    private final AuthProvider authProvider;

    /**
     * 创建认证插件。
     */
    public AuthPlugin(AuthProvider authProvider) {
        this.authProvider = authProvider;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public PluginPhase phase() {
        return PluginPhase.REQUEST;
    }

    @Override
    public int defaultPriority() {
        return DEFAULT_PRIORITY;
    }

    @Override
    public Class<?> configType() {
        return Config.class;
    }

    @Override
    public PluginResult execute(PluginContext context, PluginConfig pluginConfig) {
        Config cfg = pluginConfig.getTypedConfig(Config.class);

        if (!cfg.enabled) {
            context.setAttribute("authRequired", false);
            context.setAttribute("authPassed", false);
            return PluginResult.doContinue();
        }

        context.setAttribute("authRequired", true);
        context.getRequest().headers().remove(USER_ID_HEADER);

        EffectiveSecurityConfig.Auth authConfig = cfg.toEffectiveAuth();

        AuthenticationResult authResult;
        try {
            authResult = authProvider.authenticate(context.getRequest(), authConfig);
        } catch (Exception ex) {
            log.error("认证提供方执行异常 routeId={}", context.getRoute().getId(), ex);
            if (cfg.shadow) {
                context.setAttribute("authPassed", false);
                return PluginResult.doContinue();
            }
            if (cfg.failClosed) {
                context.setAttribute("authPassed", false);
                return PluginResult.shortCircuit(HttpResponseStatus.UNAUTHORIZED,
                        "Unauthorized", NAME, "auth_provider_error", null);
            }
            context.setAttribute("authPassed", false);
            return PluginResult.doContinue();
        }

        if (authResult.isAuthenticated()) {
            context.setUserId(authResult.getUserId());
            if (authResult.getUserId() != null && !authResult.getUserId().isBlank()) {
                context.getRequest().headers().set(USER_ID_HEADER, authResult.getUserId());
            }
            context.getRequest().headers().remove(authConfig.getTokenExtractor().getTokenHeaderName());
            context.setAttribute("authPassed", true);
            return PluginResult.doContinue();
        }

        if (cfg.shadow) {
            context.setAttribute("authPassed", false);
            return PluginResult.doContinue();
        }
        if (cfg.failClosed) {
            context.setAttribute("authPassed", false);
            return PluginResult.shortCircuit(HttpResponseStatus.UNAUTHORIZED,
                    "Unauthorized", NAME, authResult.getReason(), null);
        }
        context.setAttribute("authPassed", false);
        return PluginResult.doContinue();
    }

    /**
     * Auth 插件配置。
     */
    public static class Config {

        private boolean enabled = true;
        private boolean shadow = false;

        @JsonProperty("fail-closed")
        private boolean failClosed = true;

        private String type = "JWT";

        @JsonProperty("token-extractor")
        private TokenExtractorConfig tokenExtractor = new TokenExtractorConfig();

        private ProvidersConfig providers = new ProvidersConfig();

        /**
         * 转换为 EffectiveSecurityConfig.Auth 供 AuthProvider 使用。
         */
        public EffectiveSecurityConfig.Auth toEffectiveAuth() {
            SecurityProperties.AuthType authType = SecurityProperties.AuthType.valueOf(type);

            EffectiveSecurityConfig.TokenExtractor te = new EffectiveSecurityConfig.TokenExtractor(
                    tokenExtractor.tokenHeaderName, tokenExtractor.tokenValuePrefix);

            JwtConfig jc = providers.jwt;
            EffectiveSecurityConfig.Jwt jwt = new EffectiveSecurityConfig.Jwt(
                    jc.issuer, jc.audience, jc.publicKey, jc.jwksUrl,
                    jc.jwksRefreshSeconds, jc.jwksConnectTimeoutMillis, jc.jwksReadTimeoutMillis);

            EffectiveSecurityConfig.Providers pr = new EffectiveSecurityConfig.Providers(jwt);
            return new EffectiveSecurityConfig.Auth(enabled, shadow, failClosed, authType, te, pr);
        }

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

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public TokenExtractorConfig getTokenExtractor() {
            return tokenExtractor;
        }

        public void setTokenExtractor(TokenExtractorConfig tokenExtractor) {
            this.tokenExtractor = tokenExtractor;
        }

        public ProvidersConfig getProviders() {
            return providers;
        }

        public void setProviders(ProvidersConfig providers) {
            this.providers = providers;
        }
    }

    /**
     * Token 提取配置。
     */
    public static class TokenExtractorConfig {

        @JsonProperty("token-header-name")
        private String tokenHeaderName = "Authorization";

        @JsonProperty("token-value-prefix")
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
    public static class ProvidersConfig {

        private JwtConfig jwt = new JwtConfig();

        public JwtConfig getJwt() {
            return jwt;
        }

        public void setJwt(JwtConfig jwt) {
            this.jwt = jwt;
        }
    }

    /**
     * JWT 提供器配置。
     */
    public static class JwtConfig {

        private String issuer;
        private String audience;

        @JsonProperty("public-key")
        private String publicKey;

        @JsonProperty("jwks-url")
        private String jwksUrl;

        @JsonProperty("jwks-refresh-seconds")
        private int jwksRefreshSeconds = 300;

        @JsonProperty("jwks-connect-timeout-millis")
        private int jwksConnectTimeoutMillis = 500;

        @JsonProperty("jwks-read-timeout-millis")
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
}
