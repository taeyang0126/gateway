package com.lei.gateway.plugin;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;

/**
 * 插件实例配置。
 */
public class PluginConfig {

    private static final ObjectMapper DEFAULT_MAPPER = new ObjectMapper();

    private final String pluginName;
    private final boolean enabled;
    private final int priority;
    private final Map<String, Object> config;
    private final Object typedConfig;

    /**
     * 创建插件实例配置。
     */
    public PluginConfig(String pluginName, boolean enabled, int priority, Map<String, Object> config) {
        this(pluginName, enabled, priority, config, null);
    }

    /**
     * 创建带强类型配置的插件实例配置。
     */
    public PluginConfig(String pluginName, boolean enabled, int priority,
            Map<String, Object> config, Object typedConfig) {
        this.pluginName = pluginName;
        this.enabled = enabled;
        this.priority = priority;
        this.config = config;
        this.typedConfig = typedConfig;
    }

    /**
     * 创建带自动绑定的插件实例配置。
     *
     * @param pluginName 插件名称
     * @param enabled    是否启用
     * @param priority   优先级
     * @param config     原始 Map 配置
     * @param configType 强类型配置类，null 表示不绑定
     * @return 插件配置实例
     */
    public static PluginConfig of(String pluginName, boolean enabled, int priority,
            Map<String, Object> config, Class<?> configType) {
        Map<String, Object> effectiveConfig = config != null ? config : Map.of();
        Object typed = null;
        if (configType != null) {
            typed = DEFAULT_MAPPER.convertValue(effectiveConfig, configType);
        }
        return new PluginConfig(pluginName, enabled, priority, effectiveConfig, typed);
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

    /**
     * 获取强类型配置对象。
     *
     * @param clazz 期望的配置类型
     * @param <T>   配置类型
     * @return 强类型配置对象，如果未绑定则返回 null
     */
    public <T> T getTypedConfig(Class<T> clazz) {
        if (typedConfig == null) {
            return null;
        }
        return clazz.cast(typedConfig);
    }
}
