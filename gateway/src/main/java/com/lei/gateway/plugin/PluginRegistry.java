package com.lei.gateway.plugin;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 插件注册表，管理所有可用插件的名称到实例映射。
 */
public class PluginRegistry {

    private static final Logger log = LoggerFactory.getLogger(PluginRegistry.class);

    private final Map<String, Plugin> plugins = new HashMap<>();

    /**
     * 注册插件。同名插件重复注册抛出 IllegalStateException。
     */
    public void register(Plugin plugin) {
        String name = plugin.name();
        if (plugins.containsKey(name)) {
            throw new IllegalStateException("插件名称重复: " + name);
        }
        plugins.put(name, plugin);
        log.info("注册插件: name={} phase={} defaultPriority={}",
                name, plugin.phase(), plugin.defaultPriority());
    }

    /**
     * 按名称查找插件。
     */
    public Optional<Plugin> find(String name) {
        return Optional.ofNullable(plugins.get(name));
    }

    /**
     * 从 Plugin Bean 列表中发现并注册所有插件。
     */
    public void discoverAndRegister(List<Plugin> pluginBeans) {
        for (Plugin plugin : pluginBeans) {
            register(plugin);
        }
    }
}
