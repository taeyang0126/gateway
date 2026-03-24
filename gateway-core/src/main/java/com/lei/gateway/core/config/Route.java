package com.lei.gateway.core.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

/**
 * 路由规则配置。
 */
public class Route {

    @NotBlank
    private String id;
    @NotBlank
    private String pathPrefix;
    @NotBlank
    private String upstream;
    private Integer timeoutSeconds;
    private Long maxRequestSize;
    /** 路由优先级，数字越小优先级越高，默认 0。 */
    private int priority = 0;
    @Valid
    private RouteSecurityProperties security;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getPathPrefix() {
        return pathPrefix;
    }

    public void setPathPrefix(String pathPrefix) {
        this.pathPrefix = pathPrefix;
    }

    public String getUpstream() {
        return upstream;
    }

    public void setUpstream(String upstream) {
        this.upstream = upstream;
    }

    public Integer getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(Integer timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    public Long getMaxRequestSize() {
        return maxRequestSize;
    }

    public void setMaxRequestSize(Long maxRequestSize) {
        this.maxRequestSize = maxRequestSize;
    }

    public int getPriority() {
        return priority;
    }

    public void setPriority(int priority) {
        this.priority = priority;
    }

    public RouteSecurityProperties getSecurity() {
        return security;
    }

    public void setSecurity(RouteSecurityProperties security) {
        this.security = security;
    }
}
