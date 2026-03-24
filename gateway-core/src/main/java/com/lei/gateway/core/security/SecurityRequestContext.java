package com.lei.gateway.core.security;

import com.lei.gateway.core.config.Route;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpRequest;
import java.util.HashMap;
import java.util.Map;

/**
 * 安全过滤请求上下文。
 */
public class SecurityRequestContext {

    private final ChannelHandlerContext channelHandlerContext;
    private final HttpRequest request;
    private final Route route;
    private final String routeId;
    private final EffectiveSecurityConfig securityConfig;
    private final String traceId;

    private String clientIp;
    private String userId;
    private final Map<String, String> traceTags = new HashMap<>();

    /**
     * 创建请求上下文。
     */
    public SecurityRequestContext(ChannelHandlerContext channelHandlerContext,
            HttpRequest request, Route route,
            EffectiveSecurityConfig securityConfig, String traceId) {
        this.channelHandlerContext = channelHandlerContext;
        this.request = request;
        this.route = route;
        this.routeId = route.getId();
        this.securityConfig = securityConfig;
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

    public String getRouteId() {
        return routeId;
    }

    public EffectiveSecurityConfig getSecurityConfig() {
        return securityConfig;
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

    public Map<String, String> getTraceTags() {
        return new HashMap<>(traceTags);
    }

    /**
     * 写入过滤阶段追踪标签。
     */
    public void putTraceTag(String key, String value) {
        if (key != null && value != null) {
            traceTags.put(key, value);
        }
    }
}
