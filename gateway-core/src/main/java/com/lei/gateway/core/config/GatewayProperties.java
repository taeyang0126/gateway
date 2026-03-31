package com.lei.gateway.core.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 网关核心配置，绑定 {@code gateway} 前缀。
 */
@Validated
@ConfigurationProperties(prefix = "gateway")
public class GatewayProperties {

    private int port = 8080;
    @NotNull
    @Valid
    private List<Route> routes = new ArrayList<>();

    @Valid
    private List<PluginConfigEntry> plugins = new ArrayList<>();

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public List<Route> getRoutes() {
        return new ArrayList<>(routes);
    }

    public void setRoutes(List<Route> routes) {
        this.routes = new ArrayList<>(routes);
    }

    /**
     * 获取全局插件配置列表。
     */
    public List<PluginConfigEntry> getPlugins() {
        return new ArrayList<>(plugins);
    }

    /**
     * 设置全局插件配置列表。
     */
    public void setPlugins(List<PluginConfigEntry> plugins) {
        this.plugins = plugins == null ? new ArrayList<>() : new ArrayList<>(plugins);
    }
}
