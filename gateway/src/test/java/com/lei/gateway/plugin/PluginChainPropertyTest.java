package com.lei.gateway.plugin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.lei.gateway.config.Route;
import com.lei.gateway.observability.MetricsCollector;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpRequest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;
import org.mockito.Mockito;

/**
 * Feature: plugin-system, Property 1: 插件链按优先级执行且全 CONTINUE 正确完成。
 *
 * <p>验证 PluginChain 按 priority 升序执行所有插件，全部 CONTINUE 时返回 CONTINUE。
 */
class PluginChainPropertyTest {

    private final MetricsCollector metricsCollector = mock(MetricsCollector.class);
    private final PluginChain chain = new PluginChain(metricsCollector);

    /**
     * 对于任意同一阶段的插件列表（每个插件有随机优先级且全部返回 CONTINUE），
     * PluginChain 执行后应满足：(a) 所有插件都被调用，(b) 调用顺序严格按 priority 升序，(c) 最终结果为 CONTINUE。
     */
    @Property(tries = 100)
    // Feature: plugin-system, Property 1: 插件链按优先级执行且全 CONTINUE 正确完成
    void allContinuePluginsExecuteInPriorityOrder(
            @ForAll @Size(min = 1, max = 20) List<@IntRange(min = 0, max = 10000) Integer> priorities) {

        List<Integer> sortedPriorities = new ArrayList<>(priorities);
        Collections.sort(sortedPriorities);

        AtomicInteger callOrder = new AtomicInteger(0);
        List<Integer> actualOrder = Collections.synchronizedList(new ArrayList<>());

        List<Plugin> plugins = new ArrayList<>();
        Map<String, PluginConfig> configs = new HashMap<>();

        for (int ii = 0; ii < sortedPriorities.size(); ii++) {
            int priority = sortedPriorities.get(ii);
            String name = "plugin-" + ii;
            int index = ii;

            plugins.add(new Plugin() {
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
                    actualOrder.add(index);
                    callOrder.incrementAndGet();
                    return PluginResult.doContinue();
                }
            });
            configs.put(name, new PluginConfig(name, true, priority, Map.of()));
        }

        PluginContext context = createContext();
        PluginResult result = chain.execute(plugins, configs, context, "test-route");

        // (a) 所有插件都被调用
        assertThat(callOrder.get()).isEqualTo(sortedPriorities.size());

        // (b) 调用顺序严格按列表顺序（已按 priority 排序）
        List<Integer> expectedOrder = new ArrayList<>();
        for (int ii = 0; ii < sortedPriorities.size(); ii++) {
            expectedOrder.add(ii);
        }
        assertThat(actualOrder).isEqualTo(expectedOrder);

        // (c) 最终结果为 CONTINUE
        assertThat(result.getType()).isEqualTo(PluginResultType.CONTINUE);
        assertThat(result.isContinue()).isTrue();
    }

    /**
     * 空插件列表执行后返回 CONTINUE。
     */
    @Property(tries = 100)
    // Feature: plugin-system, Property 1: 插件链按优先级执行且全 CONTINUE 正确完成
    void emptyPluginListReturnsContinue() {
        PluginContext context = createContext();
        PluginResult result = chain.execute(List.of(), Map.of(), context, "test-route");

        assertThat(result.getType()).isEqualTo(PluginResultType.CONTINUE);
    }

    private PluginContext createContext() {
        return new PluginContext(
                Mockito.mock(ChannelHandlerContext.class),
                Mockito.mock(HttpRequest.class),
                new Route(),
                "trace-chain");
    }
}
