package com.lei.gateway.core.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;

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
    /**
     * 路径重写正则表达式（Java 正则），匹配请求路径（含 query string）。
     * 与 {@link #rewriteReplacement} 配合使用，为空则不做路径重写。
     * 示例：{@code ^/api/perf/route1(.*)}
     */
    private String rewritePath;

    /**
     * 路径重写替换字符串，支持数字捕获组引用（如 {@code $1}、{@code $2}）。
     * 注意：不支持命名捕获组引用（{@code ${name}}），YAML 中会被 Spring 当 placeholder 解析。
     * 示例：{@code /api/example$1}
     */
    private String rewriteReplacement;

    @Valid
    private RouteSecurityProperties security;

    private List<PluginConfigEntry> plugins;

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

    public String getRewritePath() {
        return rewritePath;
    }

    public void setRewritePath(String rewritePath) {
        this.rewritePath = rewritePath;
    }

    public String getRewriteReplacement() {
        return rewriteReplacement;
    }

    public void setRewriteReplacement(String rewriteReplacement) {
        this.rewriteReplacement = rewriteReplacement;
    }

    public List<PluginConfigEntry> getPlugins() {
        return plugins;
    }

    public void setPlugins(List<PluginConfigEntry> plugins) {
        this.plugins = plugins;
    }
}
