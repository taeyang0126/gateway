package com.lei.gateway.core.plugin;

import com.lei.gateway.core.config.SecurityProperties;
import com.lei.gateway.core.security.AuthProvider;
import com.lei.gateway.core.security.AuthenticationResult;
import com.lei.gateway.core.security.EffectiveSecurityConfig;
import io.netty.handler.codec.http.HttpResponseStatus;
import java.util.Collections;
import java.util.Map;
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
    public PluginResult execute(PluginContext context, PluginConfig config) {
        Map<String, Object> configMap = config.getConfig();
        if (configMap == null) {
            configMap = Collections.emptyMap();
        }

        boolean enabled = toBoolean(configMap.get("enabled"), true);
        if (!enabled) {
            context.setAttribute("authRequired", false);
            context.setAttribute("authPassed", false);
            return PluginResult.doContinue();
        }

        context.setAttribute("authRequired", true);
        boolean shadow = toBoolean(configMap.get("shadow"), false);
        boolean failClosed = toBoolean(configMap.get("fail-closed"), true);

        context.getRequest().headers().remove(USER_ID_HEADER);

        EffectiveSecurityConfig.Auth authConfig = buildAuthConfig(configMap);

        AuthenticationResult authResult;
        try {
            authResult = authProvider.authenticate(context.getRequest(), authConfig);
        } catch (Exception ex) {
            log.error("认证提供方执行异常 routeId={}", context.getRoute().getId(), ex);
            if (shadow) {
                context.setAttribute("authPassed", false);
                return PluginResult.doContinue();
            }
            if (failClosed) {
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

        if (shadow) {
            context.setAttribute("authPassed", false);
            return PluginResult.doContinue();
        }
        if (failClosed) {
            context.setAttribute("authPassed", false);
            return PluginResult.shortCircuit(HttpResponseStatus.UNAUTHORIZED,
                    "Unauthorized", NAME, authResult.getReason(), null);
        }
        context.setAttribute("authPassed", false);
        return PluginResult.doContinue();
    }

    @SuppressWarnings("unchecked")
    private static EffectiveSecurityConfig.Auth buildAuthConfig(Map<String, Object> configMap) {
        boolean enabled = toBoolean(configMap.get("enabled"), true);
        boolean shadow = toBoolean(configMap.get("shadow"), false);
        boolean failClosed = toBoolean(configMap.get("fail-closed"), true);

        String typeStr = toString(configMap.get("type"), "JWT");
        SecurityProperties.AuthType authType = SecurityProperties.AuthType.valueOf(typeStr);

        Map<String, Object> tokenExtractorMap =
                (Map<String, Object>) configMap.getOrDefault("token-extractor", Collections.emptyMap());
        String tokenHeaderName = toString(tokenExtractorMap.get("token-header-name"), "Authorization");
        String tokenValuePrefix = toString(tokenExtractorMap.get("token-value-prefix"), "Bearer ");
        EffectiveSecurityConfig.TokenExtractor tokenExtractor =
                new EffectiveSecurityConfig.TokenExtractor(tokenHeaderName, tokenValuePrefix);

        Map<String, Object> providersMap =
                (Map<String, Object>) configMap.getOrDefault("providers", Collections.emptyMap());
        Map<String, Object> jwtMap =
                (Map<String, Object>) providersMap.getOrDefault("jwt", Collections.emptyMap());

        EffectiveSecurityConfig.Jwt jwtConfig = new EffectiveSecurityConfig.Jwt(
                toString(jwtMap.get("issuer"), null),
                toString(jwtMap.get("audience"), null),
                toString(jwtMap.get("public-key"), null),
                toString(jwtMap.get("jwks-url"), null),
                toInt(jwtMap.get("jwks-refresh-seconds"), 300),
                toInt(jwtMap.get("jwks-connect-timeout-millis"), 500),
                toInt(jwtMap.get("jwks-read-timeout-millis"), 1000));

        EffectiveSecurityConfig.Providers providers =
                new EffectiveSecurityConfig.Providers(jwtConfig);

        return new EffectiveSecurityConfig.Auth(
                enabled, shadow, failClosed, authType, tokenExtractor, providers);
    }

    private static boolean toBoolean(Object value, boolean defaultValue) {
        if (value instanceof Boolean boolVal) {
            return boolVal;
        }
        if (value instanceof String str) {
            return Boolean.parseBoolean(str);
        }
        return defaultValue;
    }

    private static String toString(Object value, String defaultValue) {
        if (value instanceof String str) {
            return str;
        }
        return defaultValue;
    }

    private static int toInt(Object value, int defaultValue) {
        if (value instanceof Integer intVal) {
            return intVal;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String str && !str.isBlank()) {
            return Integer.parseInt(str);
        }
        return defaultValue;
    }
}
