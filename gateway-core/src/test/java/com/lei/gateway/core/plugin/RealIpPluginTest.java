package com.lei.gateway.core.plugin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.lei.gateway.core.config.Route;
import com.lei.gateway.core.security.ClientIpResolver;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RealIpPluginTest {

    private ClientIpResolver clientIpResolver;
    private RealIpPlugin plugin;
    private ChannelHandlerContext mockCtx;

    @BeforeEach
    void setUp() {
        clientIpResolver = mock(ClientIpResolver.class);
        plugin = new RealIpPlugin(clientIpResolver);
        mockCtx = mock(ChannelHandlerContext.class);
    }

    @Test
    void namePhaseAndPriority() {
        assertThat(plugin.name()).isEqualTo("real-ip");
        assertThat(plugin.phase()).isEqualTo(PluginPhase.REQUEST);
        assertThat(plugin.defaultPriority()).isEqualTo(1000);
    }

    @Test
    void resolvesClientIpWithTrustedProxies() {
        List<String> proxies = List.of("10.0.0.0/8");
        when(clientIpResolver.resolve(any(), any(), eq(proxies), eq(null)))
                .thenReturn("192.168.1.100");

        PluginContext context = createContext();
        PluginConfig config = PluginConfig.of("real-ip", true, 1000,
                Map.of("trusted-proxies", proxies), RealIpPlugin.Config.class);

        PluginResult result = plugin.execute(context, config);

        assertThat(result.isContinue()).isTrue();
        assertThat(context.getClientIp()).isEqualTo("192.168.1.100");
    }

    @Test
    void resolvesClientIpWithTrustedProxyHops() {
        when(clientIpResolver.resolve(any(), any(), eq(Collections.emptyList()), eq(2)))
                .thenReturn("172.16.0.50");

        PluginContext context = createContext();
        PluginConfig config = PluginConfig.of("real-ip", true, 1000,
                Map.of("trusted-proxy-hops", 2), RealIpPlugin.Config.class);

        PluginResult result = plugin.execute(context, config);

        assertThat(result.isContinue()).isTrue();
        assertThat(context.getClientIp()).isEqualTo("172.16.0.50");
    }

    @Test
    void emptyConfigUsesDefaults() {
        when(clientIpResolver.resolve(any(), any(), eq(Collections.emptyList()), eq(null)))
                .thenReturn("127.0.0.1");

        PluginContext context = createContext();
        PluginConfig config = PluginConfig.of("real-ip", true, 1000, Map.of(), RealIpPlugin.Config.class);

        PluginResult result = plugin.execute(context, config);

        assertThat(result.isContinue()).isTrue();
        assertThat(context.getClientIp()).isEqualTo("127.0.0.1");
    }

    @Test
    void nullConfigMapUsesDefaults() {
        when(clientIpResolver.resolve(any(), any(), eq(Collections.emptyList()), eq(null)))
                .thenReturn("127.0.0.1");

        PluginContext context = createContext();
        PluginConfig config = PluginConfig.of("real-ip", true, 1000, null, RealIpPlugin.Config.class);

        PluginResult result = plugin.execute(context, config);

        assertThat(result.isContinue()).isTrue();
        assertThat(context.getClientIp()).isEqualTo("127.0.0.1");
    }

    private PluginContext createContext() {
        Route route = new Route();
        route.setId("test-route");
        route.setPathPrefix("/api");
        route.setUpstream("http://localhost:8080");
        return new PluginContext(mockCtx,
                new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/api/test"),
                route, "trace-1");
    }
}
