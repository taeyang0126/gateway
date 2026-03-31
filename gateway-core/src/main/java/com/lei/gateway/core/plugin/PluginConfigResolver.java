package com.lei.gateway.core.plugin;

import com.lei.gateway.core.config.PluginConfigEntry;
import com.lei.gateway.core.config.Route;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 插件配置解析器，合并全局配置与路由级覆盖，生成最终生效的插件配置列表。
 */
public class PluginConfigResolver {

    private static final Logger log = LoggerFactory.getLogger(PluginConfigResolver.class);

    private final PluginRegistry pluginRegistry;

    /**
     * 创建插件配置解析器。
     */
    public PluginConfigResolver(PluginRegistry pluginRegistry) {
        this.pluginRegistry = pluginRegistry;
    }

    /**
     * 合并全局插件配置与路由级覆盖，生成最终生效的插件配置列表。
     *
     * @param globalPlugins 全局插件配置
     * @param route         当前路由（含路由级插件配置）
     * @return 按 phase 分组、按 priority 排序的生效插件配置
     */
    public Map<PluginPhase, List<PluginConfig>> resolve(
            List<PluginConfigEntry> globalPlugins, Route route) {
        List<PluginConfigEntry> effective = globalPlugins;
        if (effective == null) {
            effective = Collections.emptyList();
        }

        List<PluginConfigEntry> routePlugins = route.getPlugins();

        // 合并：全局为基础，路由同名覆盖，路由新增追加
        Map<String, PluginConfigEntry> merged = new LinkedHashMap<>();
        for (PluginConfigEntry entry : effective) {
            if (entry.getName() != null) {
                merged.put(entry.getName(), entry);
            }
        }
        if (routePlugins != null) {
            for (PluginConfigEntry routeEntry : routePlugins) {
                if (routeEntry.getName() != null) {
                    merged.put(routeEntry.getName(), routeEntry);
                }
            }
        }

        // 过滤 enabled=false，构建 PluginConfig，按 phase 分组
        Map<PluginPhase, List<PluginConfig>> result = new EnumMap<>(PluginPhase.class);
        for (PluginConfigEntry entry : merged.values()) {
            if (entry.getEnabled() != null && !entry.getEnabled()) {
                continue;
            }
            Optional<Plugin> pluginOpt = pluginRegistry.find(entry.getName());
            if (pluginOpt.isEmpty()) {
                log.warn("插件未注册: {}", entry.getName());
                continue;
            }
            Plugin plugin = pluginOpt.get();
            int priority = entry.getPriority() != null
                    ? entry.getPriority() : plugin.defaultPriority();
            PluginConfig config = new PluginConfig(
                    entry.getName(), true, priority,
                    entry.getConfig() != null ? entry.getConfig() : Map.of());

            result.computeIfAbsent(plugin.phase(), ph -> new ArrayList<>()).add(config);
        }

        // 每组内按 priority 升序排列
        for (List<PluginConfig> configs : result.values()) {
            configs.sort(Comparator.comparingInt(PluginConfig::getPriority));
        }

        return result;
    }
}
