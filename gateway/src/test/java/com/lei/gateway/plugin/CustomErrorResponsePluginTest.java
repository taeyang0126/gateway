package com.lei.gateway.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import com.lei.gateway.config.Route;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CustomErrorResponsePluginTest {

    private final CustomErrorResponsePlugin plugin = new CustomErrorResponsePlugin();
    private static final ChannelHandlerContext DUMMY_CTX = null;

    @Test
    void namePhasePriorityAndConfigType() {
        assertThat(plugin.name()).isEqualTo("custom-error-response");
        assertThat(plugin.phase()).isEqualTo(PluginPhase.ERROR);
        assertThat(plugin.defaultPriority()).isEqualTo(1000);
        assertThat(plugin.configType()).isEqualTo(CustomErrorResponsePlugin.Config.class);
    }

    @Test
    void executeUsesStatusSpecificMessageAndEscapesJson() {
        PluginContext context = createContext("trace-500");
        context.setResponseStatusCode(500);

        PluginConfig config = PluginConfig.of("custom-error-response", true, 1000,
                Map.of(
                        "content-type", "application/problem+json",
                        "default-message", "fallback",
                        "body-template", "{\"status\":${status},\"msg\":\"${message}\",\"trace\":\"${traceId}\"}",
                        "messages", Map.of("500", "boom \"x\"\\nline\\r")
                ),
                CustomErrorResponsePlugin.Config.class);

        PluginResult result = plugin.execute(context, config);

        assertThat(result.getType()).isEqualTo(PluginResultType.SHORT_CIRCUIT);
        assertThat(result.getStatus()).isEqualTo(HttpResponseStatus.INTERNAL_SERVER_ERROR);
        assertThat(result.getPluginName()).isEqualTo("custom-error-response");
        assertThat(result.getReason()).isEqualTo("custom_error");
        assertThat(result.getContentType()).isEqualTo("application/problem+json");
        assertThat(result.getResponseHeaders()).isEmpty();
        assertThat(result.getBody())
                .contains("\"status\":500")
                .contains("\"trace\":\"trace-500\"")
                .contains("boom \\\"x\\\"\\\\nline\\\\r");
    }

    @Test
    void executeDefaultsTo502AndDefaultMessageWhenStatusMissing() {
        PluginContext context = createContext(null);
        context.setResponseStatusCode(0);

        PluginConfig config = PluginConfig.of("custom-error-response", true, 1000,
                Map.of("default-message", "fallback error"),
                CustomErrorResponsePlugin.Config.class);

        PluginResult result = plugin.execute(context, config);

        assertThat(result.getType()).isEqualTo(PluginResultType.SHORT_CIRCUIT);
        assertThat(result.getStatus()).isEqualTo(HttpResponseStatus.BAD_GATEWAY);
        assertThat(result.getBody())
                .contains("\"code\":502")
                .contains("\"message\":\"fallback error\"")
                .contains("\"traceId\":\"\"");
    }

    @Test
    void configDefaultsAndAccessors() {
        CustomErrorResponsePlugin.Config config = new CustomErrorResponsePlugin.Config();

        assertThat(config.getContentType()).isEqualTo("application/json");
        assertThat(config.getDefaultMessage()).isEqualTo("An error occurred");
        assertThat(config.getBodyTemplate())
                .contains("${status}")
                .contains("${message}")
                .contains("${traceId}");
        assertThat(config.getMessages()).isEmpty();

        config.setContentType("application/problem+json");
        config.setDefaultMessage("bad gateway");
        config.setBodyTemplate("{\"code\":${status},\"msg\":\"${message}\"}");
        config.setMessages(Map.of("503", "upstream unavailable"));

        assertThat(config.getContentType()).isEqualTo("application/problem+json");
        assertThat(config.getDefaultMessage()).isEqualTo("bad gateway");
        assertThat(config.getBodyTemplate()).contains("${status}");
        assertThat(config.getMessages()).containsEntry("503", "upstream unavailable");
    }

    private PluginContext createContext(String traceId) {
        Route route = new Route();
        route.setId("test-route");
        route.setPathPrefix("/api");
        route.setUpstream("http://localhost:8080");
        DefaultHttpRequest request = new DefaultHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.GET, "/api/orders");
        return new PluginContext(DUMMY_CTX, request, route, traceId);
    }
}
