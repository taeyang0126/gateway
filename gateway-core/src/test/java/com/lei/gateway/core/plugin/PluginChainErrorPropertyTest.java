package com.lei.gateway.core.plugin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.lei.gateway.core.config.Route;
import com.lei.gateway.core.observability.MetricsCollector;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import org.mockito.Mockito;

/**
 * Feature: plugin-system, Property 3: 插件异常产生 ERROR 结果。
 *
 * <p>验证插件抛出异常时，PluginChain 捕获异常并返回 ERROR(500)，后续插件不被执行。
 */
class PluginChainErrorPropertyTest {

    private final MetricsCollector metricsCollector = mock(MetricsCollector.class);
    private final PluginChain chain = new PluginChain(metricsCollector);

    @Property(tries = 100)
    // Feature: plugin-system, Property 3: 插件异常产生 ERROR 结果
    void exceptionProducesErrorResult(
            @ForAll @IntRange(min = 2, max = 10) int chainLength,
            @ForAll @IntRange(min = 1, max = 10) int errorPos) {

        int effectivePos = Math.min(errorPos, chainLength);
        AtomicInteger callCount = new AtomicInteger(0);

        List<Plugin> plugins = new ArrayList<>();
        Map<String, PluginConfig> configs = new HashMap<>();

        for (int ii = 0; ii < chainLength; ii++) {
            String name = "plugin-" + ii;
            int priority = (ii + 1) * 100;
            boolean isError = (ii == effectivePos - 1);

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
                    callCount.incrementAndGet();
                    if (isError) {
                        throw new RuntimeException("test exception from " + name);
                    }
                    return PluginResult.doContinue();
                }
            });
            configs.put(name, new PluginConfig(name, true, priority, Map.of()));
        }

        PluginContext context = createContext();
        PluginResult result = chain.execute(plugins, configs, context, "test-route");

        // (a) 第 1 到 K 个插件被调用
        assertThat(callCount.get()).isEqualTo(effectivePos);

        // (b) 返回 ERROR 类型
        assertThat(result.getType()).isEqualTo(PluginResultType.ERROR);

        // (c) HTTP 500
        assertThat(result.getStatus()).isEqualTo(HttpResponseStatus.INTERNAL_SERVER_ERROR);

        // (d) 包含错误信息
        assertThat(result.getBody()).isEqualTo("Internal plugin error");
    }

    /**
     * 异常插件的 traceTag 应记录为 ERROR。
     */
    @Property(tries = 100)
    // Feature: plugin-system, Property 3: 插件异常产生 ERROR 结果
    void exceptionPluginTraceTagIsError(
            @ForAll @IntRange(min = 1, max = 5) int chainLength) {

        String errorPluginName = "plugin-0";
        List<Plugin> plugins = new ArrayList<>();
        Map<String, PluginConfig> configs = new HashMap<>();

        plugins.add(new Plugin() {
            @Override
            public String name() {
                return errorPluginName;
            }

            @Override
            public PluginPhase phase() {
                return PluginPhase.REQUEST;
            }

            @Override
            public int defaultPriority() {
                return 100;
            }

            @Override
            public PluginResult execute(PluginContext context, PluginConfig config) {
                throw new RuntimeException("boom");
            }
        });
        configs.put(errorPluginName, new PluginConfig(errorPluginName, true, 100, Map.of()));

        PluginContext context = createContext();
        chain.execute(plugins, configs, context, "test-route");

        assertThat(context.getTraceTags()).containsEntry(errorPluginName, "ERROR");
    }

    private PluginContext createContext() {
        return new PluginContext(
                Mockito.mock(ChannelHandlerContext.class),
                Mockito.mock(HttpRequest.class),
                new Route(),
                "trace-err");
    }
}
