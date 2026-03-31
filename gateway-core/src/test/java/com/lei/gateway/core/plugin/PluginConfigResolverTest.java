package com.lei.gateway.core.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lei.gateway.core.config.PluginConfigEntry;
import com.lei.gateway.core.config.Route;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * PluginConfigResolver 单元测试。
 */
class PluginConfigResolverTest {

    private PluginRegistry registry;
    private PluginConfigResolver resolver;

    @BeforeEach
    void setUp() {
        registry = new PluginRegistry();
        registry.register(stubPlugin("real-ip", PluginPhase.REQUEST, 1000));
        registry.register(stubPlugin("ip-access", PluginPhase.REQUEST, 2000));
        registry.register(stubPlugin("auth", PluginPhase.REQUEST, 4000));

        resolver = new PluginConfigResolver(registry, new ObjectMapper());
    }

    @Test
    void routeWithoutPlugins_usesGlobalConfig() {
        PluginConfigEntry realIp = entry("real-ip", true, 1000);
        PluginConfigEntry ipAccess = entry("ip-access", true, 2000);

        Route route = new Route();
        route.setId("no-plugins-route");

        Map<PluginPhase, List<PluginConfig>> result =
                resolver.resolve(List.of(realIp, ipAccess), route);

        List<PluginConfig> requestPlugins = result.get(PluginPhase.REQUEST);
        assertThat(requestPlugins).hasSize(2);
        assertThat(requestPlugins.get(0).getPluginName()).isEqualTo("real-ip");
        assertThat(requestPlugins.get(1).getPluginName()).isEqualTo("ip-access");
    }

    @Test
    void routeDisablesGlobalPlugin() {
        PluginConfigEntry realIp = entry("real-ip", true, 1000);
        PluginConfigEntry ipAccess = entry("ip-access", true, 2000);
        PluginConfigEntry auth = entry("auth", true, 4000);

        PluginConfigEntry routeDisableIpAccess = entry("ip-access", false, null);

        Route route = new Route();
        route.setId("disable-route");
        route.setPlugins(List.of(routeDisableIpAccess));

        Map<PluginPhase, List<PluginConfig>> result =
                resolver.resolve(List.of(realIp, ipAccess, auth), route);

        List<PluginConfig> requestPlugins = result.get(PluginPhase.REQUEST);
        assertThat(requestPlugins).hasSize(2);
        List<String> names = requestPlugins.stream()
                .map(PluginConfig::getPluginName).toList();
        assertThat(names).containsExactly("real-ip", "auth");
    }

    @Test
    void unregisteredPluginSkipped() {
        PluginConfigEntry unknown = entry("nonexistent-plugin", true, 500);

        Route route = new Route();
        route.setId("unknown-route");

        Map<PluginPhase, List<PluginConfig>> result =
                resolver.resolve(List.of(unknown), route);

        assertThat(result.getOrDefault(PluginPhase.REQUEST, List.of())).isEmpty();
    }

    @Test
    void defaultPriorityUsedWhenEntryPriorityNull() {
        PluginConfigEntry entryNoPriority = new PluginConfigEntry();
        entryNoPriority.setName("auth");
        entryNoPriority.setEnabled(true);

        Route route = new Route();
        route.setId("default-priority-route");

        Map<PluginPhase, List<PluginConfig>> result =
                resolver.resolve(List.of(entryNoPriority), route);

        List<PluginConfig> requestPlugins = result.get(PluginPhase.REQUEST);
        assertThat(requestPlugins).hasSize(1);
        assertThat(requestPlugins.get(0).getPriority()).isEqualTo(4000);
    }

    @Test
    void emptyGlobalAndRoutePlugins_returnsEmptyMap() {
        Route route = new Route();
        route.setId("empty-route");

        Map<PluginPhase, List<PluginConfig>> result =
                resolver.resolve(List.of(), route);

        assertThat(result).isEmpty();
    }

    private PluginConfigEntry entry(String name, boolean enabled, Integer priority) {
        PluginConfigEntry ce = new PluginConfigEntry();
        ce.setName(name);
        ce.setEnabled(enabled);
        ce.setPriority(priority);
        return ce;
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
