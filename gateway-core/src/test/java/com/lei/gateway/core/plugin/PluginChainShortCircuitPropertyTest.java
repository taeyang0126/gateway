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
 * Feature: plugin-system, Property 2: SHORT_CIRCUIT 终止后续插件执行。
 *
 * <p>验证第 K 个插件返回 SHORT_CIRCUIT 时，后续插件不被调用，且返回结果与第 K 个插件一致。
 */
class PluginChainShortCircuitPropertyTest {

    private final MetricsCollector metricsCollector = mock(MetricsCollector.class);
    private final PluginChain chain = new PluginChain(metricsCollector);

    @Property(tries = 100)
    // Feature: plugin-system, Property 2: SHORT_CIRCUIT 终止后续插件执行
    void shortCircuitStopsRemainingPlugins(
            @ForAll @IntRange(min = 2, max = 10) int chainLength,
            @ForAll @IntRange(min = 1, max = 10) int shortCircuitPos,
            @ForAll @IntRange(min = 400, max = 499) int statusCode,
            @ForAll String responseBody) {

        int effectivePos = Math.min(shortCircuitPos, chainLength);
        HttpResponseStatus httpStatus = HttpResponseStatus.valueOf(statusCode);

        AtomicInteger callCount = new AtomicInteger(0);
        List<Plugin> plugins = new ArrayList<>();
        Map<String, PluginConfig> configs = new HashMap<>();

        for (int ii = 0; ii < chainLength; ii++) {
            String name = "plugin-" + ii;
            int priority = (ii + 1) * 100;
            boolean isShortCircuit = (ii == effectivePos - 1);

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
                    if (isShortCircuit) {
                        return PluginResult.shortCircuit(
                                httpStatus, responseBody, name, "test-reason", null);
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

        // (b) 返回 SHORT_CIRCUIT
        assertThat(result.getType()).isEqualTo(PluginResultType.SHORT_CIRCUIT);

        // (c) 状态码和响应体与第 K 个插件一致
        assertThat(result.getStatus()).isEqualTo(httpStatus);
        assertThat(result.getBody()).isEqualTo(responseBody);
        assertThat(result.getPluginName()).isEqualTo("plugin-" + (effectivePos - 1));
    }

    private PluginContext createContext() {
        return new PluginContext(
                Mockito.mock(ChannelHandlerContext.class),
                Mockito.mock(HttpRequest.class),
                new Route(),
                "trace-sc");
    }
}
