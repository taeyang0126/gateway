package com.lei.gateway.plugin;

import com.lei.gateway.config.Route;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpRequest;
import java.util.HashMap;
import java.util.Map;

/**
 * 插件执行上下文，在整个请求生命周期内共享。
 */
public class PluginContext {

    private final ChannelHandlerContext channelHandlerContext;
    private final HttpRequest request;
    private final Route route;
    private final String traceId;

    private String clientIp;
    private String userId;

    private final Map<String, Object> attributes = new HashMap<>();
    private final Map<String, String> traceTags = new HashMap<>();

    /**
     * 创建插件执行上下文。
     */
    public PluginContext(ChannelHandlerContext channelHandlerContext,
            HttpRequest request, Route route, String traceId) {
        this.channelHandlerContext = channelHandlerContext;
        this.request = request;
        this.route = route;
        this.traceId = traceId;
    }

    public ChannelHandlerContext getChannelHandlerContext() {
        return channelHandlerContext;
    }

    public HttpRequest getRequest() {
        return request;
    }

    public Route getRoute() {
        return route;
    }

    public String getTraceId() {
        return traceId;
    }

    public String getClientIp() {
        return clientIp;
    }

    public void setClientIp(String clientIp) {
        this.clientIp = clientIp;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    /**
     * 设置共享属性。
     */
    public void setAttribute(String key, Object value) {
        attributes.put(key, value);
    }

    /**
     * 获取共享属性。
     */
    @SuppressWarnings("unchecked")
    public <T> T getAttribute(String key, Class<T> type) {
        return (T) attributes.get(key);
    }

    /**
     * 写入插件追踪标签。
     */
    public void putTraceTag(String key, String value) {
        if (key != null && value != null) {
            traceTags.put(key, value);
        }
    }

    /**
     * 获取所有追踪标签的副本。
     */
    public Map<String, String> getTraceTags() {
        return new HashMap<>(traceTags);
    }
}
