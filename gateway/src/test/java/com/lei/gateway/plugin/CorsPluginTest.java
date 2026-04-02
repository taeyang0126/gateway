package com.lei.gateway.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import com.lei.gateway.config.Route;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CorsPluginTest {

    private final CorsPlugin plugin = new CorsPlugin();
    private static final ChannelHandlerContext DUMMY_CTX = null;

    @Test
    void namePhasePriorityAndConfigType() {
        assertThat(plugin.name()).isEqualTo("cors");
        assertThat(plugin.phase()).isEqualTo(PluginPhase.REQUEST);
        assertThat(plugin.defaultPriority()).isEqualTo(500);
        assertThat(plugin.configType()).isEqualTo(CorsPlugin.Config.class);
    }

    @Test
    void noOriginHeaderReturnsContinue() {
        PluginContext context = createContext(HttpMethod.GET, "/api/orders", null, false);
        PluginConfig config = PluginConfig.of("cors", true, 500, Map.of(), CorsPlugin.Config.class);

        PluginResult result = plugin.execute(context, config);

        assertThat(result.isContinue()).isTrue();
        assertThat(context.getAttribute(CorsPlugin.CORS_HEADERS_KEY, Map.class)).isNull();
    }

    @Test
    void disallowedOriginReturnsContinue() {
        PluginContext context = createContext(HttpMethod.GET, "/api/orders",
                "https://evil.example", false);
        PluginConfig config = PluginConfig.of("cors", true, 500,
                Map.of("allow-origins", List.of("https://app.example")),
                CorsPlugin.Config.class);

        PluginResult result = plugin.execute(context, config);

        assertThat(result.isContinue()).isTrue();
        assertThat(context.getAttribute(CorsPlugin.CORS_HEADERS_KEY, Map.class)).isNull();
    }

    @Test
    void preflightRequestShortCircuitsWithCorsHeaders() {
        PluginContext context = createContext(HttpMethod.OPTIONS, "/api/orders",
                "https://app.example", true);
        PluginConfig config = PluginConfig.of("cors", true, 500,
                Map.of(
                        "allow-origins", List.of("https://app.example"),
                        "allow-methods", List.of("GET", "POST"),
                        "allow-headers", List.of("Authorization", "X-Trace-Id"),
                        "allow-credentials", true,
                        "max-age", 600
                ),
                CorsPlugin.Config.class);

        PluginResult result = plugin.execute(context, config);

        assertThat(result.getType()).isEqualTo(PluginResultType.SHORT_CIRCUIT);
        assertThat(result.getStatus()).isEqualTo(HttpResponseStatus.NO_CONTENT);
        assertThat(result.getPluginName()).isEqualTo("cors");
        assertThat(result.getReason()).isEqualTo("preflight");
        assertThat(result.getContentType()).isEqualTo("text/plain");
        assertThat(result.getResponseHeaders())
                .containsEntry("Access-Control-Allow-Origin", "https://app.example")
                .containsEntry("Access-Control-Allow-Credentials", "true")
                .containsEntry("Access-Control-Allow-Methods", "GET, POST")
                .containsEntry("Access-Control-Allow-Headers", "Authorization, X-Trace-Id")
                .containsEntry("Access-Control-Max-Age", "600");
    }

    @Test
    void nonPreflightStoresHeadersInContext() {
        PluginContext context = createContext(HttpMethod.GET, "/api/orders",
                "https://app.example", false);
        PluginConfig config = PluginConfig.of("cors", true, 500,
                Map.of(
                        "allow-origins", List.of("https://app.example"),
                        "expose-headers", List.of("X-Trace-Id", "X-Request-Id")
                ),
                CorsPlugin.Config.class);

        PluginResult result = plugin.execute(context, config);

        assertThat(result.isContinue()).isTrue();
        Map<String, String> headers = context.getAttribute(CorsPlugin.CORS_HEADERS_KEY, Map.class);
        assertThat(headers)
                .containsEntry("Access-Control-Allow-Origin", "https://app.example")
                .containsEntry("Access-Control-Expose-Headers", "X-Trace-Id, X-Request-Id");
    }

    @Test
    void wildcardAllowOriginResolvesToStar() {
        PluginContext context = createContext(HttpMethod.GET, "/api/orders",
                "https://any.example", false);
        PluginConfig config = PluginConfig.of("cors", true, 500,
                Map.of(
                        "allow-origins", List.of("*"),
                        "allow-credentials", true
                ),
                CorsPlugin.Config.class);

        PluginResult result = plugin.execute(context, config);

        assertThat(result.isContinue()).isTrue();
        Map<String, String> headers = context.getAttribute(CorsPlugin.CORS_HEADERS_KEY, Map.class);
        assertThat(headers)
                .containsEntry("Access-Control-Allow-Origin", "*")
                .containsEntry("Access-Control-Allow-Credentials", "true");
    }

    @Test
    void configDefaultsAndAccessors() {
        CorsPlugin.Config config = new CorsPlugin.Config();

        assertThat(config.getAllowOrigins()).containsExactly("*");
        assertThat(config.getAllowMethods())
                .containsExactly("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS");
        assertThat(config.getAllowHeaders()).containsExactly("*");
        assertThat(config.getExposeHeaders()).isEmpty();
        assertThat(config.isAllowCredentials()).isFalse();
        assertThat(config.getMaxAge()).isEqualTo(86400);

        config.setAllowOrigins(List.of("https://app.example"));
        config.setAllowMethods(List.of("GET"));
        config.setAllowHeaders(List.of("Authorization"));
        config.setExposeHeaders(List.of("X-Trace-Id"));
        config.setAllowCredentials(true);
        config.setMaxAge(1200);

        assertThat(config.getAllowOrigins()).containsExactly("https://app.example");
        assertThat(config.getAllowMethods()).containsExactly("GET");
        assertThat(config.getAllowHeaders()).containsExactly("Authorization");
        assertThat(config.getExposeHeaders()).containsExactly("X-Trace-Id");
        assertThat(config.isAllowCredentials()).isTrue();
        assertThat(config.getMaxAge()).isEqualTo(1200);
    }

    private PluginContext createContext(HttpMethod method, String uri,
            String origin, boolean preflight) {
        Route route = new Route();
        route.setId("test-route");
        route.setPathPrefix("/api");
        route.setUpstream("http://localhost:8080");

        DefaultHttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, method, uri);
        if (origin != null) {
            request.headers().set(HttpHeaderNames.ORIGIN, origin);
        }
        if (preflight) {
            request.headers().set("Access-Control-Request-Method", "POST");
        }
        return new PluginContext(DUMMY_CTX, request, route, "trace-cors");
    }
}
