package com.lei.gateway.plugin;

import io.netty.handler.codec.http.HttpResponseStatus;
import java.util.Collections;
import java.util.Map;

/**
 * 插件执行结果。
 */
public class PluginResult {

    private final PluginResultType type;
    private final HttpResponseStatus status;
    private final String body;
    private final String pluginName;
    private final String reason;
    private final Integer retryAfterSeconds;
    private final Map<String, String> responseHeaders;
    private final String contentType;

    private PluginResult(PluginResultType type, HttpResponseStatus status, String body,
            String pluginName, String reason, Integer retryAfterSeconds,
            Map<String, String> responseHeaders, String contentType) {
        this.type = type;
        this.status = status;
        this.body = body;
        this.pluginName = pluginName;
        this.reason = reason;
        this.retryAfterSeconds = retryAfterSeconds;
        this.responseHeaders = responseHeaders != null ? responseHeaders : Collections.emptyMap();
        this.contentType = contentType;
    }

    /**
     * 生成继续执行结果。
     */
    public static PluginResult doContinue() {
        return new PluginResult(PluginResultType.CONTINUE, null, null,
                null, null, null, null, null);
    }

    /**
     * 生成短路终止结果。
     */
    public static PluginResult shortCircuit(HttpResponseStatus status, String body,
            String pluginName, String reason, Integer retryAfterSeconds) {
        return new PluginResult(PluginResultType.SHORT_CIRCUIT, status, body,
                pluginName, reason, retryAfterSeconds, null, null);
    }

    /**
     * 生成带自定义响应头和 Content-Type 的短路终止结果。
     */
    public static PluginResult shortCircuit(HttpResponseStatus status, String body,
            String pluginName, String reason, Integer retryAfterSeconds,
            Map<String, String> responseHeaders, String contentType) {
        return new PluginResult(PluginResultType.SHORT_CIRCUIT, status, body,
                pluginName, reason, retryAfterSeconds, responseHeaders, contentType);
    }

    /**
     * 生成错误结果。
     */
    public static PluginResult error(HttpResponseStatus status, String body) {
        return new PluginResult(PluginResultType.ERROR, status, body,
                null, null, null, null, null);
    }

    /**
     * 是否继续执行。
     */
    public boolean isContinue() {
        return type == PluginResultType.CONTINUE;
    }

    public PluginResultType getType() {
        return type;
    }

    public HttpResponseStatus getStatus() {
        return status;
    }

    public String getBody() {
        return body;
    }

    public String getPluginName() {
        return pluginName;
    }

    public String getReason() {
        return reason;
    }

    public Integer getRetryAfterSeconds() {
        return retryAfterSeconds;
    }

    /**
     * 获取自定义响应头。
     */
    public Map<String, String> getResponseHeaders() {
        return responseHeaders;
    }

    /**
     * 获取自定义 Content-Type，null 表示使用默认（application/json）。
     */
    public String getContentType() {
        return contentType;
    }
}
