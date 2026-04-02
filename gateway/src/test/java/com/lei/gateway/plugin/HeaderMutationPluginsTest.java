package com.lei.gateway.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import com.lei.gateway.config.Route;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HeaderMutationPluginsTest {

    private static final ChannelHandlerContext DUMMY_CTX = null;

    @Test
    void addRequestHeaderPluginCoversBranchesAndConfig() {
        AddRequestHeaderPlugin plugin = new AddRequestHeaderPlugin();

        assertThat(plugin.name()).isEqualTo("add-request-header");
        assertThat(plugin.phase()).isEqualTo(PluginPhase.REQUEST);
        assertThat(plugin.defaultPriority()).isEqualTo(6000);
        assertThat(plugin.configType()).isEqualTo(AddRequestHeaderPlugin.Config.class);

        PluginContext context = createContext(HttpMethod.GET, "/api/orders");
        PluginConfig emptyConfig = PluginConfig.of("add-request-header", true, 6000,
                Map.of(), AddRequestHeaderPlugin.Config.class);
        assertThat(plugin.execute(context, emptyConfig).isContinue()).isTrue();

        PluginConfig config = PluginConfig.of("add-request-header", true, 6000,
                Map.of("headers", Map.of("X-Env", "prod", "X-Trace-Id", "t-1")),
                AddRequestHeaderPlugin.Config.class);
        assertThat(plugin.execute(context, config).isContinue()).isTrue();
        assertThat(context.getRequest().headers().get("X-Env")).isEqualTo("prod");
        assertThat(context.getRequest().headers().get("X-Trace-Id")).isEqualTo("t-1");

        AddRequestHeaderPlugin.Config typed = new AddRequestHeaderPlugin.Config();
        assertThat(typed.getHeaders()).isEmpty();
        typed.setHeaders(Map.of("A", "B"));
        assertThat(typed.getHeaders()).containsEntry("A", "B");
    }

    @Test
    void addResponseHeaderPluginCoversBranchesAndConfig() {
        AddResponseHeaderPlugin plugin = new AddResponseHeaderPlugin();

        assertThat(plugin.name()).isEqualTo("add-response-header");
        assertThat(plugin.phase()).isEqualTo(PluginPhase.RESPONSE);
        assertThat(plugin.defaultPriority()).isEqualTo(1000);
        assertThat(plugin.configType()).isEqualTo(AddResponseHeaderPlugin.Config.class);

        PluginContext context = createContext(HttpMethod.GET, "/api/orders");
        PluginConfig config = PluginConfig.of("add-response-header", true, 1000,
                Map.of("headers", Map.of("X-Upstream", "gateway")),
                AddResponseHeaderPlugin.Config.class);

        assertThat(plugin.execute(context, config).isContinue()).isTrue();

        DefaultHttpResponse upstream = new DefaultHttpResponse(HttpVersion.HTTP_1_1,
                HttpResponseStatus.OK);
        context.setUpstreamResponse(upstream);
        assertThat(plugin.execute(context, config).isContinue()).isTrue();
        assertThat(upstream.headers().get("X-Upstream")).isEqualTo("gateway");

        AddResponseHeaderPlugin.Config typed = new AddResponseHeaderPlugin.Config();
        assertThat(typed.getHeaders()).isEmpty();
        typed.setHeaders(Map.of("Server", "hidden"));
        assertThat(typed.getHeaders()).containsEntry("Server", "hidden");
    }

    @Test
    void removeRequestHeaderPluginCoversBranchesAndConfig() {
        RemoveRequestHeaderPlugin plugin = new RemoveRequestHeaderPlugin();

        assertThat(plugin.name()).isEqualTo("remove-request-header");
        assertThat(plugin.phase()).isEqualTo(PluginPhase.REQUEST);
        assertThat(plugin.defaultPriority()).isEqualTo(6100);
        assertThat(plugin.configType()).isEqualTo(RemoveRequestHeaderPlugin.Config.class);

        PluginContext context = createContext(HttpMethod.GET, "/api/orders");
        context.getRequest().headers().set("Authorization", "Bearer t");
        context.getRequest().headers().set("X-Keep", "1");

        PluginConfig config = PluginConfig.of("remove-request-header", true, 6100,
                Map.of("headers", List.of("Authorization")),
                RemoveRequestHeaderPlugin.Config.class);

        assertThat(plugin.execute(context, config).isContinue()).isTrue();
        assertThat(context.getRequest().headers().get("Authorization")).isNull();
        assertThat(context.getRequest().headers().get("X-Keep")).isEqualTo("1");

        RemoveRequestHeaderPlugin.Config typed = new RemoveRequestHeaderPlugin.Config();
        assertThat(typed.getHeaders()).isEmpty();
        typed.setHeaders(List.of("A", "B"));
        assertThat(typed.getHeaders()).containsExactly("A", "B");
    }

    @Test
    void removeResponseHeaderPluginCoversBranchesAndConfig() {
        RemoveResponseHeaderPlugin plugin = new RemoveResponseHeaderPlugin();

        assertThat(plugin.name()).isEqualTo("remove-response-header");
        assertThat(plugin.phase()).isEqualTo(PluginPhase.RESPONSE);
        assertThat(plugin.defaultPriority()).isEqualTo(1100);
        assertThat(plugin.configType()).isEqualTo(RemoveResponseHeaderPlugin.Config.class);

        PluginContext context = createContext(HttpMethod.GET, "/api/orders");
        PluginConfig config = PluginConfig.of("remove-response-header", true, 1100,
                Map.of("headers", List.of("Server")),
                RemoveResponseHeaderPlugin.Config.class);

        assertThat(plugin.execute(context, config).isContinue()).isTrue();

        DefaultHttpResponse upstream = new DefaultHttpResponse(HttpVersion.HTTP_1_1,
                HttpResponseStatus.OK);
        upstream.headers().set("Server", "nginx");
        upstream.headers().set("X-Keep", "1");
        context.setUpstreamResponse(upstream);

        assertThat(plugin.execute(context, config).isContinue()).isTrue();
        assertThat(upstream.headers().get("Server")).isNull();
        assertThat(upstream.headers().get("X-Keep")).isEqualTo("1");

        RemoveResponseHeaderPlugin.Config typed = new RemoveResponseHeaderPlugin.Config();
        assertThat(typed.getHeaders()).isEmpty();
        typed.setHeaders(List.of("X-Powered-By"));
        assertThat(typed.getHeaders()).containsExactly("X-Powered-By");
    }

    @Test
    void rewritePathPluginCoversChangedAndUnchangedPathsAndConfig() {
        RewritePathPlugin plugin = new RewritePathPlugin();

        assertThat(plugin.name()).isEqualTo("rewrite-path");
        assertThat(plugin.phase()).isEqualTo(PluginPhase.REQUEST);
        assertThat(plugin.defaultPriority()).isEqualTo(6200);
        assertThat(plugin.configType()).isEqualTo(RewritePathPlugin.Config.class);

        PluginContext changedContext = createContext(HttpMethod.GET, "/api/v1/orders?id=1");
        PluginConfig changedConfig = PluginConfig.of("rewrite-path", true, 6200,
                Map.of("pattern", "^/api/v1/(.*)", "replacement", "/backend/$1"),
                RewritePathPlugin.Config.class);

        assertThat(plugin.execute(changedContext, changedConfig).isContinue()).isTrue();
        assertThat(changedContext.getAttribute(RewritePathPlugin.REWRITTEN_URI_KEY, String.class))
                .isEqualTo("/backend/orders?id=1");

        PluginContext unchangedContext = createContext(HttpMethod.GET, "/static/health");
        assertThat(plugin.execute(unchangedContext, changedConfig).isContinue()).isTrue();
        assertThat(unchangedContext.getAttribute(RewritePathPlugin.REWRITTEN_URI_KEY, String.class))
                .isNull();

        RewritePathPlugin.Config typed = new RewritePathPlugin.Config();
        typed.setPattern("^/old/(.*)");
        typed.setReplacement("/new/$1");
        assertThat(typed.getPattern()).isEqualTo("^/old/(.*)");
        assertThat(typed.getReplacement()).isEqualTo("/new/$1");
    }

    @Test
    void pluginConfigBindExceptionExposesPluginNameAndCause() {
        RuntimeException cause = new RuntimeException("bad value");

        PluginConfigBindException ex = new PluginConfigBindException("cors", "invalid", cause);

        assertThat(ex.getPluginName()).isEqualTo("cors");
        assertThat(ex.getCause()).isSameAs(cause);
        assertThat(ex.getMessage()).contains("插件 [cors] 配置绑定失败: invalid");
    }

    private PluginContext createContext(HttpMethod method, String uri) {
        Route route = new Route();
        route.setId("test-route");
        route.setPathPrefix("/api");
        route.setUpstream("http://localhost:8080");

        DefaultHttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, method, uri);
        return new PluginContext(DUMMY_CTX, request, route, "trace-header");
    }
}
