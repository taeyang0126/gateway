package com.lei.gateway.core.plugin;

import com.lei.gateway.core.security.RateLimitResult;
import com.lei.gateway.core.security.RateLimiterEngine;
import io.netty.handler.codec.http.HttpResponseStatus;
import java.util.Collections;
import java.util.Map;

/**
 * 按客户端 IP 限流插件。
 *
 * <p>超限返回 429 + Retry-After。shadow 模式下超限只记日志不拦截。
 */
public class IpRateLimitPlugin implements Plugin {

    private static final String NAME = "ip-rate-limit";
    private static final int DEFAULT_PRIORITY = 3000;
    private static final int DEFAULT_PERMITS_PER_SECOND = 100;
    private static final int DEFAULT_BURST_CAPACITY = 200;

    private final RateLimiterEngine rateLimiterEngine;

    /**
     * 创建 IP 限流插件。
     */
    public IpRateLimitPlugin(RateLimiterEngine rateLimiterEngine) {
        this.rateLimiterEngine = rateLimiterEngine;
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
            return PluginResult.doContinue();
        }

        boolean shadow = toBoolean(configMap.get("shadow"), false);
        int permitsPerSecond = toInt(configMap.get("permits-per-second"), DEFAULT_PERMITS_PER_SECOND);
        int burstCapacity = toInt(configMap.get("burst-capacity"), DEFAULT_BURST_CAPACITY);

        String key = "ip:" + context.getClientIp();
        RateLimitResult result = rateLimiterEngine.allow(key, permitsPerSecond, burstCapacity);

        if (result.isAllowed()) {
            return PluginResult.doContinue();
        }

        if (shadow) {
            return PluginResult.doContinue();
        }

        return PluginResult.shortCircuit(HttpResponseStatus.TOO_MANY_REQUESTS,
                "Too Many Requests", NAME, "rate_limited",
                result.getRetryAfterSeconds());
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
