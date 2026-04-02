package com.lei.gateway.plugin;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Collections;
import java.util.List;

/**
 * 删除响应头插件，在返回给客户端前移除指定上游响应头。
 *
 * <p>常用于隐藏上游内部头（如 Server、X-Powered-By）。
 */
public class RemoveResponseHeaderPlugin implements Plugin {

    private static final String NAME = "remove-response-header";
    private static final int DEFAULT_PRIORITY = 1100;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public PluginPhase phase() {
        return PluginPhase.RESPONSE;
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
        io.netty.handler.codec.http.HttpResponse response =
                context.getUpstreamResponse();
        if (response == null) {
            return PluginResult.doContinue();
        }
        for (String header : cfg.headers) {
            response.headers().remove(header);
        }
        return PluginResult.doContinue();
    }

    /**
     * RemoveResponseHeader 插件配置。
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
