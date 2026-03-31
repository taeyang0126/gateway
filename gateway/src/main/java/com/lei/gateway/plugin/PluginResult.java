package com.lei.gateway.plugin;

import io.netty.handler.codec.http.HttpResponseStatus;

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

    private PluginResult(PluginResultType type, HttpResponseStatus status, String body,
            String pluginName, String reason, Integer retryAfterSeconds) {
        this.type = type;
        this.status = status;
        this.body = body;
        this.pluginName = pluginName;
        this.reason = reason;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    /**
     * 生成继续执行结果。
     */
    public static PluginResult doContinue() {
        return new PluginResult(PluginResultType.CONTINUE, null, null, null, null, null);
    }

    /**
     * 生成短路终止结果。
     */
    public static PluginResult shortCircuit(HttpResponseStatus status, String body,
            String pluginName, String reason, Integer retryAfterSeconds) {
        return new PluginResult(PluginResultType.SHORT_CIRCUIT, status, body,
                pluginName, reason, retryAfterSeconds);
    }

    /**
     * 生成错误结果。
     */
    public static PluginResult error(HttpResponseStatus status, String body) {
        return new PluginResult(PluginResultType.ERROR, status, body, null, null, null);
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
}
