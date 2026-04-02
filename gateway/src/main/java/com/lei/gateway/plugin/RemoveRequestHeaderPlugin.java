package com.lei.gateway.plugin;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Collections;
import java.util.List;

/**
 * 删除请求头插件，在转发给上游前移除指定请求头。
 */
public class RemoveRequestHeaderPlugin implements Plugin {

    private static final String NAME = "remove-request-header";
    private static final int DEFAULT_PRIORITY = 6100;

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
        for (String header : cfg.headers) {
            context.getRequest().headers().remove(header);
        }
        return PluginResult.doContinue();
    }

    /**
     * RemoveRequestHeader 插件配置。
     */
    public static class Config {

        @JsonProperty("headers")
        private List<String> headers = Collections.emptyList();

        public List<String> getHeaders() {
            return headers;
        }

        public void setHeaders(List<String> headers) {
            this.headers = headers;
        }
    }
}
