package com.lei.gateway.core.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import com.lei.gateway.core.config.Route;
import com.lei.gateway.core.security.CidrMatcher;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.mock;

class IpAccessPluginTest {

    private IpAccessPlugin plugin;
    private ChannelHandlerContext mockCtx;

    @BeforeEach
    void setUp() {
        plugin = new IpAccessPlugin(new CidrMatcher());
        mockCtx = mock(ChannelHandlerContext.class);
    }

    @Test
    void namePhaseAndPriority() {
        assertThat(plugin.name()).isEqualTo("ip-access");
        assertThat(plugin.phase()).isEqualTo(PluginPhase.REQUEST);
        assertThat(plugin.defaultPriority()).isEqualTo(2000);
    }

    @Test
    void denyListBlocksMatchingIp() {
        PluginContext context = createContext("192.168.1.100");
        PluginConfig config = new PluginConfig("ip-access", true, 2000,
                Map.of("deny-list", List.of("192.168.1.100")));

        PluginResult result = plugin.execute(context, config);

        assertThat(result.getType()).isEqualTo(PluginResultType.SHORT_CIRCUIT);
        assertThat(result.getStatus()).isEqualTo(HttpResponseStatus.FORBIDDEN);
        assertThat(result.getReason()).isEqualTo("ip_in_deny_list");
    }

    @Test
    void denyListTakesPriorityOverAllowList() {
        PluginContext context = createContext("192.168.1.100");
        PluginConfig config = new PluginConfig("ip-access", true, 2000,
                Map.of("deny-list", List.of("192.168.1.0/24"),
                        "allow-list", List.of("192.168.1.0/24")));

        PluginResult result = plugin.execute(context, config);

        assertThat(result.getType()).isEqualTo(PluginResultType.SHORT_CIRCUIT);
        assertThat(result.getStatus()).isEqualTo(HttpResponseStatus.FORBIDDEN);
    }

    @Test
    void allowListBlocksNonMatchingIp() {
        PluginContext context = createContext("10.0.0.1");
        PluginConfig config = new PluginConfig("ip-access", true, 2000,
                Map.of("allow-list", List.of("192.168.1.0/24")));

        PluginResult result = plugin.execute(context, config);

        assertThat(result.getType()).isEqualTo(PluginResultType.SHORT_CIRCUIT);
        assertThat(result.getStatus()).isEqualTo(HttpResponseStatus.FORBIDDEN);
        assertThat(result.getReason()).isEqualTo("ip_not_in_allow_list");
    }

    @Test
    void allowListPermitsMatchingIp() {
        PluginContext context = createContext("192.168.1.50");
        PluginConfig config = new PluginConfig("ip-access", true, 2000,
                Map.of("allow-list", List.of("192.168.1.0/24")));

        PluginResult result = plugin.execute(context, config);

        assertThat(result.isContinue()).isTrue();
    }

    @Test
    void shadowModeDenyReturnsContine() {
        PluginContext context = createContext("192.168.1.100");
        PluginConfig config = new PluginConfig("ip-access", true, 2000,
                Map.of("shadow", true,
                        "deny-list", List.of("192.168.1.100")));

        PluginResult result = plugin.execute(context, config);

        assertThat(result.isContinue()).isTrue();
    }

    @Test
    void shadowModeNotInAllowListReturnsContinue() {
        PluginContext context = createContext("10.0.0.1");
        PluginConfig config = new PluginConfig("ip-access", true, 2000,
                Map.of("shadow", true,
                        "allow-list", List.of("192.168.1.0/24")));

        PluginResult result = plugin.execute(context, config);

        assertThat(result.isContinue()).isTrue();
    }

    @Test
    void disabledReturnsContinue() {
        PluginContext context = createContext("192.168.1.100");
        PluginConfig config = new PluginConfig("ip-access", true, 2000,
                Map.of("enabled", false,
                        "deny-list", List.of("192.168.1.100")));

        PluginResult result = plugin.execute(context, config);

        assertThat(result.isContinue()).isTrue();
    }

    @Test
    void emptyListsAllowAll() {
        PluginContext context = createContext("10.0.0.1");
        PluginConfig config = new PluginConfig("ip-access", true, 2000, Map.of());

        PluginResult result = plugin.execute(context, config);

        assertThat(result.isContinue()).isTrue();
    }

    private PluginContext createContext(String clientIp) {
        Route route = new Route();
        route.setId("test-route");
        route.setPathPrefix("/api");
        route.setUpstream("http://localhost:8080");
        PluginContext context = new PluginContext(mockCtx,
                new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/api/test"),
                route, "trace-1");
        context.setClientIp(clientIp);
        return context;
    }
}
