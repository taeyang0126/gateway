package com.lei.gateway.plugin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lei.gateway.config.PluginConfigEntry;
import com.lei.gateway.config.Route;
import com.lei.gateway.observability.MetricsCollector;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * GatewayPluginProcessor 单元测试。
 */
class GatewayPluginProcessorTest {

    private MetricsCollector metricsCollector;
    private PluginRegistry registry;
    private PluginConfigResolver resolver;
    private PluginChain chain;

    @BeforeEach
    void setUp() {
        metricsCollector = mock(MetricsCollector.class);
        registry = new PluginRegistry();
        resolver = new PluginConfigResolver(registry, new ObjectMapper());
        chain = new PluginChain(metricsCollector);
    }

    @Test
    void executeRequestPhaseRunsPluginChain() {
        Plugin pluginA = createPlugin("plugin-a", 1000, PluginResult.doContinue());
        Plugin pluginB = createPlugin("plugin-b", 2000,
                PluginResult.shortCircuit(HttpResponseStatus.FORBIDDEN, "denied",
                        "plugin-b", "ip-blocked", null));
        registry.register(pluginA);
        registry.register(pluginB);

        PluginConfigEntry entryA = new PluginConfigEntry();
        entryA.setName("plugin-a");
        entryA.setPriority(1000);
        PluginConfigEntry entryB = new PluginConfigEntry();
        entryB.setName("plugin-b");
        entryB.setPriority(2000);

        GatewayPluginProcessor processor = new GatewayPluginProcessor(
                registry, resolver, chain, List.of(entryA, entryB));

        Route route = createRoute("test-route");
        HttpRequest request = new DefaultHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.GET, "/api/test");

        PluginExecutionResult result = processor.executeRequestPhase(
                mock(ChannelHandlerContext.class), request, route, "trace-1");

        assertThat(result.isContinue()).isFalse();
        assertThat(result.getResult().getType()).isEqualTo(PluginResultType.SHORT_CIRCUIT);
        assertThat(result.getResult().getStatus()).isEqualTo(HttpResponseStatus.FORBIDDEN);
        assertThat(result.getResult().getPluginName()).isEqualTo("plugin-b");

        // traceTags 包含已执行插件
        Map<String, String> tags = result.getContext().getTraceTags();
        assertThat(tags).containsEntry("plugin-a", "CONTINUE");
        assertThat(tags).containsEntry("plugin-b", "SHORT_CIRCUIT");
    }

    @Test
    void unregisteredPluginIsSkippedWithWarning() {
        Plugin pluginA = createPlugin("plugin-a", 1000, PluginResult.doContinue());
        registry.register(pluginA);

        PluginConfigEntry entryA = new PluginConfigEntry();
        entryA.setName("plugin-a");
        entryA.setPriority(1000);
        // plugin-missing 未注册
        PluginConfigEntry entryMissing = new PluginConfigEntry();
        entryMissing.setName("plugin-missing");
        entryMissing.setPriority(500);

        GatewayPluginProcessor processor = new GatewayPluginProcessor(
                registry, resolver, chain, List.of(entryMissing, entryA));

        Route route = createRoute("skip-route");
        HttpRequest request = new DefaultHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.GET, "/api/skip");

        PluginExecutionResult result = processor.executeRequestPhase(
                mock(ChannelHandlerContext.class), request, route, "trace-2");

        // 未注册插件被跳过，只有 plugin-a 执行，最终 CONTINUE
        assertThat(result.isContinue()).isTrue();
        Map<String, String> tags = result.getContext().getTraceTags();
        assertThat(tags).containsEntry("plugin-a", "CONTINUE");
        assertThat(tags).doesNotContainKey("plugin-missing");
    }

    @Test
    void emptyGlobalConfigReturnsContinue() {
        GatewayPluginProcessor processor = new GatewayPluginProcessor(
                registry, resolver, chain, List.of());

        Route route = createRoute("empty-route");
        HttpRequest request = new DefaultHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.GET, "/api/empty");

        PluginExecutionResult result = processor.executeRequestPhase(
                mock(ChannelHandlerContext.class), request, route, "trace-3");

        assertThat(result.isContinue()).isTrue();
    }

    @Test
    void nullGlobalConfigReturnsContinue() {
        GatewayPluginProcessor processor = new GatewayPluginProcessor(
                registry, resolver, chain, null);

        Route route = createRoute("null-route");
        HttpRequest request = new DefaultHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.GET, "/api/null");

        PluginExecutionResult result = processor.executeRequestPhase(
                mock(ChannelHandlerContext.class), request, route, "trace-4");

        assertThat(result.isContinue()).isTrue();
    }

    private Route createRoute(String id) {
        Route route = new Route();
        route.setId(id);
        route.setPathPrefix("/test");
        route.setUpstream("http://localhost:8080");
        return route;
    }

    private Plugin createPlugin(String name, int priority, PluginResult result) {
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
                return result;
            }
        };
    }
}
