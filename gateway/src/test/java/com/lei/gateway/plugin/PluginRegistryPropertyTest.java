package com.lei.gateway.plugin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.AlphaChars;
import net.jqwik.api.constraints.Size;
import net.jqwik.api.constraints.StringLength;
import net.jqwik.api.constraints.UniqueElements;

/**
 * Feature: plugin-system, Property 6: PluginRegistry 注册查找往返。
 *
 * <p>验证注册后通过 find 能查找到插件实例，未注册的名称返回 empty。
 */
class PluginRegistryPropertyTest {

    @Property(tries = 100)
    // Feature: plugin-system, Property 6: PluginRegistry 注册查找往返
    void registeredPluginCanBeFound(
            @ForAll @AlphaChars @StringLength(min = 2, max = 30) String pluginName) {

        PluginRegistry registry = new PluginRegistry();
        Plugin plugin = stubPlugin(pluginName);
        registry.register(plugin);

        assertThat(registry.find(pluginName)).isPresent().containsSame(plugin);
    }

    @Property(tries = 100)
    // Feature: plugin-system, Property 6: PluginRegistry 注册查找往返
    void unregisteredPluginReturnsEmpty(
            @ForAll @AlphaChars @StringLength(min = 2, max = 30) String pluginName) {

        PluginRegistry registry = new PluginRegistry();

        assertThat(registry.find(pluginName)).isEmpty();
    }

    @Property(tries = 100)
    // Feature: plugin-system, Property 6: PluginRegistry 注册查找往返
    void multiplePluginsAllFindable(
            @ForAll @Size(min = 1, max = 10) @UniqueElements
            List<@AlphaChars @StringLength(min = 2, max = 20) String> names) {

        PluginRegistry registry = new PluginRegistry();
        for (String name : names) {
            registry.register(stubPlugin(name));
        }

        for (String name : names) {
            assertThat(registry.find(name)).isPresent();
            assertThat(registry.find(name).get().name()).isEqualTo(name);
        }
    }

    /**
     * Feature: plugin-system, Property 7: PluginRegistry 拒绝重复名称。
     */
    @Property(tries = 100)
    // Feature: plugin-system, Property 7: PluginRegistry 拒绝重复名称
    void duplicateNameThrowsIllegalStateException(
            @ForAll @AlphaChars @StringLength(min = 2, max = 30) String pluginName) {

        PluginRegistry registry = new PluginRegistry();
        registry.register(stubPlugin(pluginName));

        assertThatThrownBy(() -> registry.register(stubPlugin(pluginName)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(pluginName);
    }

    private Plugin stubPlugin(String name) {
        return new Plugin() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public PluginPhase phase() {
                return PluginPhase.REQUEST;
            }

            @Override
            public int defaultPriority() {
                return 1000;
            }

            @Override
            public PluginResult execute(PluginContext context, PluginConfig config) {
                return PluginResult.doContinue();
            }
        };
    }
}
