package com.lei.gateway.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lei.gateway.config.PluginConfigEntry;
import com.lei.gateway.config.Route;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;

/**
 * Feature: plugin-system, Property 5: 插件配置合并正确性。
 *
 * <p>验证 PluginConfigResolver.resolve() 的合并规则：
 * (a) 全局中 enabled 且路由未覆盖的插件出现在结果中，
 * (b) 路由级同名插件覆盖全局值，
 * (c) 路由级新增插件追加到结果中，
 * (d) enabled=false 的插件不出现在结果中，
 * (e) 结果按 phase 分组后每组内按 priority 升序排列。
 */
class PluginConfigResolverPropertyTest {

    /**
     * 全局 enabled 且路由未覆盖的插件出现在结果中。
     */
    @Property(tries = 100)
    // Feature: plugin-system, Property 5: 插件配置合并正确性
    void globalEnabledPluginAppearsWhenRouteDoesNotOverride(
            @ForAll @IntRange(min = 100, max = 9000) int priority) {

        PluginRegistry registry = new PluginRegistry();
        registry.register(stubPlugin("test-plugin", PluginPhase.REQUEST, priority));

        PluginConfigResolver resolver = new PluginConfigResolver(registry, new ObjectMapper());

        PluginConfigEntry globalEntry = new PluginConfigEntry();
        globalEntry.setName("test-plugin");
        globalEntry.setEnabled(true);
        globalEntry.setPriority(priority);

        Route route = new Route();
        Map<PluginPhase, List<PluginConfig>> result =
                resolver.resolve(List.of(globalEntry), route);

        assertThat(result).containsKey(PluginPhase.REQUEST);
        List<PluginConfig> requestPlugins = result.get(PluginPhase.REQUEST);
        assertThat(requestPlugins).hasSize(1);
        assertThat(requestPlugins.get(0).getPluginName()).isEqualTo("test-plugin");
        assertThat(requestPlugins.get(0).getPriority()).isEqualTo(priority);
    }

    /**
     * 路由级同名插件覆盖全局的 priority 和 config。
     */
    @Property(tries = 100)
    // Feature: plugin-system, Property 5: 插件配置合并正确性
    void routeOverridesGlobalPriorityAndConfig(
            @ForAll @IntRange(min = 100, max = 5000) int globalPriority,
            @ForAll @IntRange(min = 100, max = 5000) int routePriority) {

        PluginRegistry registry = new PluginRegistry();
        registry.register(stubPlugin("merge-plugin", PluginPhase.REQUEST, globalPriority));

        PluginConfigResolver resolver = new PluginConfigResolver(registry, new ObjectMapper());

        PluginConfigEntry globalEntry = new PluginConfigEntry();
        globalEntry.setName("merge-plugin");
        globalEntry.setPriority(globalPriority);
        globalEntry.setConfig(Map.of("key", "global-value"));

        PluginConfigEntry routeEntry = new PluginConfigEntry();
        routeEntry.setName("merge-plugin");
        routeEntry.setPriority(routePriority);
        routeEntry.setConfig(Map.of("key", "route-value"));

        Route route = new Route();
        route.setPlugins(List.of(routeEntry));

        Map<PluginPhase, List<PluginConfig>> result =
                resolver.resolve(List.of(globalEntry), route);

        List<PluginConfig> requestPlugins = result.get(PluginPhase.REQUEST);
        assertThat(requestPlugins).hasSize(1);
        assertThat(requestPlugins.get(0).getPriority()).isEqualTo(routePriority);
        assertThat(requestPlugins.get(0).getConfig()).containsEntry("key", "route-value");
    }

    /**
     * 路由级新增插件追加到结果中。
     */
    @Property(tries = 100)
    // Feature: plugin-system, Property 5: 插件配置合并正确性
    void routeAddsNewPlugin(
            @ForAll @IntRange(min = 100, max = 5000) int globalPriority,
            @ForAll @IntRange(min = 100, max = 5000) int routePriority) {

        PluginRegistry registry = new PluginRegistry();
        registry.register(stubPlugin("global-plugin", PluginPhase.REQUEST, globalPriority));
        registry.register(stubPlugin("route-only-plugin", PluginPhase.REQUEST, routePriority));

        PluginConfigResolver resolver = new PluginConfigResolver(registry, new ObjectMapper());

        PluginConfigEntry globalEntry = new PluginConfigEntry();
        globalEntry.setName("global-plugin");
        globalEntry.setPriority(globalPriority);

        PluginConfigEntry routeEntry = new PluginConfigEntry();
        routeEntry.setName("route-only-plugin");
        routeEntry.setPriority(routePriority);

        Route route = new Route();
        route.setPlugins(List.of(routeEntry));

        Map<PluginPhase, List<PluginConfig>> result =
                resolver.resolve(List.of(globalEntry), route);

        List<PluginConfig> requestPlugins = result.get(PluginPhase.REQUEST);
        assertThat(requestPlugins).hasSize(2);
        List<String> names = requestPlugins.stream()
                .map(PluginConfig::getPluginName).toList();
        assertThat(names).contains("global-plugin", "route-only-plugin");
    }

    /**
     * enabled=false 的插件不出现在结果中。
     */
    @Property(tries = 100)
    // Feature: plugin-system, Property 5: 插件配置合并正确性
    void disabledPluginExcluded(
            @ForAll @IntRange(min = 100, max = 5000) int priority) {

        PluginRegistry registry = new PluginRegistry();
        registry.register(stubPlugin("disabled-plugin", PluginPhase.REQUEST, priority));

        PluginConfigResolver resolver = new PluginConfigResolver(registry, new ObjectMapper());

        PluginConfigEntry entry = new PluginConfigEntry();
        entry.setName("disabled-plugin");
        entry.setEnabled(false);
        entry.setPriority(priority);

        Route route = new Route();
        Map<PluginPhase, List<PluginConfig>> result =
                resolver.resolve(List.of(entry), route);

        assertThat(result.getOrDefault(PluginPhase.REQUEST, List.of())).isEmpty();
    }

    /**
     * 结果按 phase 分组后每组内按 priority 升序排列。
     */
    @Property(tries = 100)
    // Feature: plugin-system, Property 5: 插件配置合并正确性
    void resultSortedByPriorityWithinPhase(
            @ForAll @Size(min = 2, max = 8) List<@IntRange(min = 1, max = 10000) Integer> priorities) {

        PluginRegistry registry = new PluginRegistry();
        List<PluginConfigEntry> entries = new ArrayList<>();

        for (int ii = 0; ii < priorities.size(); ii++) {
            String name = "sort-plugin-" + ii;
            registry.register(stubPlugin(name, PluginPhase.REQUEST, priorities.get(ii)));

            PluginConfigEntry entry = new PluginConfigEntry();
            entry.setName(name);
            entry.setPriority(priorities.get(ii));
            entries.add(entry);
        }

        PluginConfigResolver resolver = new PluginConfigResolver(registry, new ObjectMapper());
        Route route = new Route();

        Map<PluginPhase, List<PluginConfig>> result = resolver.resolve(entries, route);

        List<PluginConfig> requestPlugins = result.getOrDefault(PluginPhase.REQUEST, List.of());
        for (int ii = 1; ii < requestPlugins.size(); ii++) {
            assertThat(requestPlugins.get(ii).getPriority())
                    .isGreaterThanOrEqualTo(requestPlugins.get(ii - 1).getPriority());
        }
    }

    /**
     * 路由 enabled:false 覆盖全局 enabled 插件，排除该插件。
     */
    @Property(tries = 100)
    // Feature: plugin-system, Property 5: 插件配置合并正确性
    void routeDisablesGlobalPlugin(
            @ForAll @IntRange(min = 100, max = 5000) int priority) {

        PluginRegistry registry = new PluginRegistry();
        registry.register(stubPlugin("to-disable", PluginPhase.REQUEST, priority));

        PluginConfigResolver resolver = new PluginConfigResolver(registry, new ObjectMapper());

        PluginConfigEntry globalEntry = new PluginConfigEntry();
        globalEntry.setName("to-disable");
        globalEntry.setEnabled(true);
        globalEntry.setPriority(priority);

        PluginConfigEntry routeEntry = new PluginConfigEntry();
        routeEntry.setName("to-disable");
        routeEntry.setEnabled(false);

        Route route = new Route();
        route.setPlugins(List.of(routeEntry));

        Map<PluginPhase, List<PluginConfig>> result =
                resolver.resolve(List.of(globalEntry), route);

        assertThat(result.getOrDefault(PluginPhase.REQUEST, List.of())).isEmpty();
    }

    private Plugin stubPlugin(String name, PluginPhase phase, int defaultPriority) {
        return new Plugin() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public PluginPhase phase() {
                return phase;
            }

            @Override
            public int defaultPriority() {
                return defaultPriority;
            }

            @Override
            public PluginResult execute(PluginContext context, PluginConfig config) {
                return PluginResult.doContinue();
            }
        };
    }
}
