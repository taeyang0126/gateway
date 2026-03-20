package com.example.gateway.core.config;

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
}
