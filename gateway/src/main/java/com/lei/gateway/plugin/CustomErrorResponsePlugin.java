package com.lei.gateway.plugin;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.netty.handler.codec.http.HttpResponseStatus;
import java.util.Collections;
import java.util.Map;

/**
 * 自定义错误响应插件，统一网关错误响应格式。
 *
 * <p>在 ERROR 阶段拦截上游错误，返回自定义 JSON 结构。
 * 支持按状态码配置不同的错误消息模板。
 */
public class CustomErrorResponsePlugin implements Plugin {

    private static final String NAME = "custom-error-response";
    private static final int DEFAULT_PRIORITY = 1000;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public PluginPhase phase() {
        return PluginPhase.ERROR;
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
        int statusCode = context.getResponseStatusCode();
        if (statusCode <= 0) {
            statusCode = 502;
        }

        String message = cfg.messages.get(String.valueOf(statusCode));
        if (message == null) {
            message = cfg.defaultMessage;
        }

        String body = cfg.bodyTemplate
                .replace("${status}", String.valueOf(statusCode))
                .replace("${message}", escapeJson(message))
                .replace("${traceId}",
                        context.getTraceId() != null
                                ? context.getTraceId() : "");

        HttpResponseStatus status = HttpResponseStatus.valueOf(statusCode);
        return PluginResult.shortCircuit(status, body, NAME,
                "custom_error", null, Collections.emptyMap(),
                cfg.contentType);
    }

    private static String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }

    /**
     * CustomErrorResponse 插件配置。
     */
    public static class Config {

        @JsonProperty("content-type")
        private String contentType = "application/json";

        @JsonProperty("default-message")
        private String defaultMessage = "An error occurred";

        @JsonProperty("body-template")
        private String bodyTemplate =
                "{\"code\":${status},\"message\":\"${message}\""
                + ",\"traceId\":\"${traceId}\"}";

        @JsonProperty("messages")
        private Map<String, String> messages = Collections.emptyMap();

        public String getContentType() {
            return contentType;
        }

        public void setContentType(String contentType) {
            this.contentType = contentType;
        }

        public String getDefaultMessage() {
            return defaultMessage;
        }

        public void setDefaultMessage(String defaultMessage) {
            this.defaultMessage = defaultMessage;
        }

        public String getBodyTemplate() {
            return bodyTemplate;
        }

        public void setBodyTemplate(String bodyTemplate) {
            this.bodyTemplate = bodyTemplate;
        }

        public Map<String, String> getMessages() {
            return messages;
        }

        public void setMessages(Map<String, String> messages) {
            this.messages = messages;
        }
    }
}
