package com.lei.gateway.core.plugin;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.lei.gateway.core.security.RateLimitResult;
import com.lei.gateway.core.security.RateLimiterEngine;
import io.netty.handler.codec.http.HttpResponseStatus;

/**
 * 按 userId 限流插件。
 *
 * <p>未认证时跳过。超限返回 429 + Retry-After。shadow 模式下超限只记日志不拦截。
 */
public class UserRateLimitPlugin implements Plugin {

    private static final String NAME = "user-rate-limit";
    private static final int DEFAULT_PRIORITY = 5000;

    private final RateLimiterEngine rateLimiterEngine;

    /**
     * 创建用户限流插件。
     */
    public UserRateLimitPlugin(RateLimiterEngine rateLimiterEngine) {
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
    public Class<?> configType() {
        return Config.class;
    }

    @Override
    public PluginResult execute(PluginContext context, PluginConfig pluginConfig) {
        Config cfg = pluginConfig.getTypedConfig(Config.class);

        if (!cfg.enabled) {
            return PluginResult.doContinue();
        }

        String userId = context.getUserId();
        if (userId == null || userId.isBlank()) {
            return PluginResult.doContinue();
        }

        String key = "user:" + userId;
        RateLimitResult result = rateLimiterEngine.allow(key, cfg.permitsPerSecond, cfg.burstCapacity);

        if (result.isAllowed()) {
            return PluginResult.doContinue();
        }

        if (cfg.shadow) {
            return PluginResult.doContinue();
        }

        return PluginResult.shortCircuit(HttpResponseStatus.TOO_MANY_REQUESTS,
                "Too Many Requests", NAME, "rate_limited",
                result.getRetryAfterSeconds());
    }

    /**
     * UserRateLimit 插件配置。
     */
    public static class Config {

        private boolean enabled = true;
        private boolean shadow = false;

        @JsonProperty("permits-per-second")
        private int permitsPerSecond = 50;

        @JsonProperty("burst-capacity")
        private int burstCapacity = 100;

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
    }
}
