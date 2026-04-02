package com.lei.gateway.plugin;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Collections;
import java.util.Map;

/**
 * 添加/覆盖请求头插件，在转发给上游前修改请求头。
 */
public class AddRequestHeaderPlugin implements Plugin {

    private static final String NAME = "add-request-header";
    private static final int DEFAULT_PRIORITY = 6000;

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
    public PluginResult execute(PluginContext context,
            PluginConfig pluginConfig) {
        Config cfg = pluginConfig.getTypedConfig(Config.class);
        if (cfg.headers == null || cfg.headers.isEmpty()) {
            return PluginResult.doContinue();
        }
        for (Map.Entry<String, String> entry : cfg.headers.entrySet()) {
            context.getRequest().headers().set(entry.getKey(),
                    entry.getValue());
        }
        return PluginResult.doContinue();
    }

    /**
     * AddRequestHeader 插件配置。
     */
    public static class Config {

        @JsonProperty("headers")
        private Map<String, String> headers = Collections.emptyMap();

        public Map<String, String> getHeaders() {
            return headers;
        }

        public void setHeaders(Map<String, String> headers) {
            this.headers = headers;
        }
    }
}
