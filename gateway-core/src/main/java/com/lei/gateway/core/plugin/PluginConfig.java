package com.lei.gateway.core.plugin;

import java.util.Map;

/**
 * 插件实例配置。
 */
public class PluginConfig {

    private final String pluginName;
    private final boolean enabled;
    private final int priority;
    private final Map<String, Object> config;

    /**
     * 创建插件实例配置。
     */
    public PluginConfig(String pluginName, boolean enabled, int priority, Map<String, Object> config) {
        this.pluginName = pluginName;
        this.enabled = enabled;
        this.priority = priority;
        this.config = config;
    }

    public String getPluginName() {
        return pluginName;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public int getPriority() {
        return priority;
    }

    public Map<String, Object> getConfig() {
        return config;
    }
}
