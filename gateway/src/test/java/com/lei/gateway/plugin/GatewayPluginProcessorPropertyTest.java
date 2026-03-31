package com.lei.gateway.plugin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lei.gateway.config.PluginConfigEntry;
import com.lei.gateway.config.Route;
import com.lei.gateway.observability.MetricsCollector;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;

/**
 * Feature: plugin-system, Property 8: GatewayPluginProcessor 记录指标和追踪标签。
 *
 * <p>验证 GatewayPluginProcessor 为每个执行的插件记录 duration 和 decision 指标，
 * 且 PluginContext 的 traceTags 包含每个插件名称对应的决策标签。
 */
class GatewayPluginProcessorPropertyTest {

    /**
     * 对于任意 REQUEST 阶段插件执行（全部 CONTINUE），GatewayPluginProcessor 应为每个插件
     * 记录 duration Timer 和 decision Counter，且 traceTags 包含每个插件的决策标签。
     */
    @Property(tries = 100)
    // Feature: plugin-system, Property 8: GatewayPluginProcessor 记录指标和追踪标签
    void allContinuePluginsRecordMetricsAndTraceTags(
            @ForAll @Size(min = 1, max = 10) List<@IntRange(min = 1, max = 9999) Integer> priorities) {

        MetricsCollector metricsCollector = mock(MetricsCollector.class);
        PluginRegistry registry = new PluginRegistry();
        PluginConfigResolver resolver = new PluginConfigResolver(registry, new ObjectMapper());
        PluginChain chain = new PluginChain(metricsCollector);

        List<PluginConfigEntry> globalConfigs = new ArrayList<>();
        for (int ii = 0; ii < priorities.size(); ii++) {
            String name = "test-plugin-" + ii;
            int priority = priorities.get(ii);

            registry.register(createContinuePlugin(name, priority));

            PluginConfigEntry entry = new PluginConfigEntry();
            entry.setName(name);
            entry.setEnabled(true);
            entry.setPriority(priority);
            globalConfigs.add(entry);
        }

        GatewayPluginProcessor processor = new GatewayPluginProcessor(
                registry, resolver, chain, globalConfigs);

        Route route = new Route();
        route.setId("prop-route");
        route.setPathPrefix("/test");
        route.setUpstream("http://localhost:8080");

        PluginExecutionResult execResult = processor.executeRequestPhase(
                mock(ChannelHandlerContext.class), mock(HttpRequest.class),
                route, "trace-prop");

        // 最终结果为 CONTINUE
        assertThat(execResult.isContinue()).isTrue();

        // 每个插件都记录了 duration 和 decision 指标
        for (int ii = 0; ii < priorities.size(); ii++) {
            String name = "test-plugin-" + ii;
            verify(metricsCollector).recordPluginDuration(
                    eq(name), eq("prop-route"), anyLong());
            verify(metricsCollector).recordPluginDecision(
                    eq(name), eq("CONTINUE"), eq("prop-route"));
        }

        // traceTags 包含每个插件的决策标签
        Map<String, String> traceTags = execResult.getContext().getTraceTags();
        for (int ii = 0; ii < priorities.size(); ii++) {
            String name = "test-plugin-" + ii;
            assertThat(traceTags).containsEntry(name, "CONTINUE");
        }
    }

    /**
     * 当某个插件返回 SHORT_CIRCUIT 时，该插件及之前的插件记录指标，
     * 之后的插件不记录，traceTags 只包含已执行插件的标签。
     */
    @Property(tries = 100)
    // Feature: plugin-system, Property 8: GatewayPluginProcessor 记录指标和追踪标签
    void shortCircuitPluginRecordsMetricsUpToThatPlugin(
            @ForAll @IntRange(min = 2, max = 8) int totalPlugins,
            @ForAll @IntRange(min = 1, max = 8) int shortCircuitAt) {

        int scIndex = Math.min(shortCircuitAt, totalPlugins) - 1;

        MetricsCollector metricsCollector = mock(MetricsCollector.class);
        PluginRegistry registry = new PluginRegistry();
        PluginConfigResolver resolver = new PluginConfigResolver(registry, new ObjectMapper());
        PluginChain chain = new PluginChain(metricsCollector);

        List<PluginConfigEntry> globalConfigs = new ArrayList<>();
        for (int ii = 0; ii < totalPlugins; ii++) {
            String name = "sc-plugin-" + ii;
            int priority = (ii + 1) * 100;
            boolean isShortCircuit = (ii == scIndex);

            registry.register(isShortCircuit
                    ? createShortCircuitPlugin(name, priority)
                    : createContinuePlugin(name, priority));

            PluginConfigEntry entry = new PluginConfigEntry();
            entry.setName(name);
            entry.setEnabled(true);
            entry.setPriority(priority);
            globalConfigs.add(entry);
        }

        GatewayPluginProcessor processor = new GatewayPluginProcessor(
                registry, resolver, chain, globalConfigs);

        Route route = new Route();
        route.setId("sc-route");
        route.setPathPrefix("/sc");
        route.setUpstream("http://localhost:8080");

        PluginExecutionResult execResult = processor.executeRequestPhase(
                mock(ChannelHandlerContext.class), mock(HttpRequest.class),
                route, "trace-sc");

        assertThat(execResult.isContinue()).isFalse();

        // 已执行的插件（0 到 scIndex）记录了指标
        int executedCount = scIndex + 1;
        verify(metricsCollector, times(executedCount))
                .recordPluginDuration(anyString(), eq("sc-route"), anyLong());
        verify(metricsCollector, times(executedCount))
                .recordPluginDecision(anyString(), anyString(), eq("sc-route"));

        // traceTags 只包含已执行插件
        Map<String, String> traceTags = execResult.getContext().getTraceTags();
        assertThat(traceTags).hasSize(executedCount);
        for (int ii = 0; ii <= scIndex; ii++) {
            assertThat(traceTags).containsKey("sc-plugin-" + ii);
        }
    }

    private Plugin createContinuePlugin(String name, int priority) {
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
                return priority;
            }

            @Override
            public PluginResult execute(PluginContext context, PluginConfig config) {
                return PluginResult.doContinue();
            }
        };
    }

    private Plugin createShortCircuitPlugin(String name, int priority) {
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
                return priority;
            }

            @Override
            public PluginResult execute(PluginContext context, PluginConfig config) {
                return PluginResult.shortCircuit(
                        io.netty.handler.codec.http.HttpResponseStatus.FORBIDDEN,
                        "denied", name, "test-reason", null);
            }
        };
    }
}
