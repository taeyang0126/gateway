package com.lei.gateway.plugin;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 跨域资源共享（CORS）插件。
 *
 * <p>预检请求（OPTIONS + Origin + Access-Control-Request-Method）直接返回 204 + CORS 头。
 * 非预检请求将 CORS 响应头存入 PluginContext attribute，由 RESPONSE 阶段插件写出。
 */
public class CorsPlugin implements Plugin {

    private static final String NAME = "cors";
    private static final int DEFAULT_PRIORITY = 500;

    /** PluginContext attribute key，存储需要注入的 CORS 响应头。 */
    public static final String CORS_HEADERS_KEY = "cors.responseHeaders";

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
        String origin = context.getRequest().headers()
                .get(HttpHeaderNames.ORIGIN);
        if (origin == null || origin.isBlank()) {
            return PluginResult.doContinue();
        }

        if (!isOriginAllowed(origin, cfg.allowOrigins)) {
            return PluginResult.doContinue();
        }

        boolean isPreflight = HttpMethod.OPTIONS.name().equals(
                context.getRequest().method().name())
                && context.getRequest().headers()
                        .contains("Access-Control-Request-Method");

        Map<String, String> corsHeaders = buildCorsHeaders(origin, cfg,
                isPreflight);

        if (isPreflight) {
            return PluginResult.shortCircuit(
                    HttpResponseStatus.NO_CONTENT, "", NAME,
                    "preflight", null, corsHeaders, "text/plain");
        }

        // 非预检：存入 context，供 RESPONSE 阶段写出
        context.setAttribute(CORS_HEADERS_KEY, corsHeaders);
        return PluginResult.doContinue();
    }

    private Map<String, String> buildCorsHeaders(String origin,
            Config cfg, boolean isPreflight) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Access-Control-Allow-Origin",
                resolveAllowOrigin(origin, cfg.allowOrigins));
        if (cfg.allowCredentials) {
            headers.put("Access-Control-Allow-Credentials", "true");
        }
        if (isPreflight) {
            headers.put("Access-Control-Allow-Methods",
                    String.join(", ", cfg.allowMethods));
            headers.put("Access-Control-Allow-Headers",
                    String.join(", ", cfg.allowHeaders));
            headers.put("Access-Control-Max-Age",
                    String.valueOf(cfg.maxAge));
        } else if (cfg.exposeHeaders != null
                && !cfg.exposeHeaders.isEmpty()) {
            headers.put("Access-Control-Expose-Headers",
                    String.join(", ", cfg.exposeHeaders));
        }
        return headers;
    }

    private boolean isOriginAllowed(String origin,
            List<String> allowOrigins) {
        if (allowOrigins == null || allowOrigins.isEmpty()
                || allowOrigins.contains("*")) {
            return true;
        }
        return allowOrigins.contains(origin);
    }

    private String resolveAllowOrigin(String origin,
            List<String> allowOrigins) {
        if (allowOrigins == null || allowOrigins.isEmpty()
                || allowOrigins.contains("*")) {
            return "*";
        }
        return origin;
    }

    /**
     * CORS 插件配置。
     */
    public static class Config {

        @JsonProperty("allow-origins")
        private List<String> allowOrigins =
                Collections.singletonList("*");

        @JsonProperty("allow-methods")
        private List<String> allowMethods = List.of(
                "GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS");

        @JsonProperty("allow-headers")
        private List<String> allowHeaders = List.of("*");

        @JsonProperty("expose-headers")
        private List<String> exposeHeaders = Collections.emptyList();

        @JsonProperty("allow-credentials")
        private boolean allowCredentials = false;

        @JsonProperty("max-age")
        private int maxAge = 86400;

        public List<String> getAllowOrigins() {
            return allowOrigins;
        }

        public void setAllowOrigins(List<String> allowOrigins) {
            this.allowOrigins = allowOrigins;
        }

        public List<String> getAllowMethods() {
            return allowMethods;
        }

        public void setAllowMethods(List<String> allowMethods) {
            this.allowMethods = allowMethods;
        }

        public List<String> getAllowHeaders() {
            return allowHeaders;
        }

        public void setAllowHeaders(List<String> allowHeaders) {
            this.allowHeaders = allowHeaders;
        }

        public List<String> getExposeHeaders() {
            return exposeHeaders;
        }

        public void setExposeHeaders(List<String> exposeHeaders) {
            this.exposeHeaders = exposeHeaders;
        }

        public boolean isAllowCredentials() {
            return allowCredentials;
        }

        public void setAllowCredentials(boolean allowCredentials) {
            this.allowCredentials = allowCredentials;
        }

        public int getMaxAge() {
            return maxAge;
        }

        public void setMaxAge(int maxAge) {
            this.maxAge = maxAge;
        }
    }
}
