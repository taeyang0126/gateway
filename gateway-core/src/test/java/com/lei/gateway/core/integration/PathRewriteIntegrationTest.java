package com.lei.gateway.core.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.lei.gateway.core.config.GatewayProperties;
import com.lei.gateway.core.config.Route;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 路径重写（path rewrite）端到端集成测试。
 *
 * <p>验证 rewritePath + rewriteReplacement 配置能正确改写上游请求路径。
 */
class PathRewriteIntegrationTest extends IntegrationTestBase {

    @Override
    protected GatewayProperties createGatewayProperties() {
        GatewayProperties props = new GatewayProperties();
        props.setPort(0);

        // 路由1：前缀替换 /api/perf/route1/** → /api/example/**
        Route rewriteRoute = createRoute(
                "perf-route-1",
                "/api/perf/route1/**",
                "http://localhost:" + upstreamPort);
        rewriteRoute.setRewritePath("^/api/perf/route1(.*)");
        rewriteRoute.setRewriteReplacement("/api/example$1");

        // 路由2：固定路径重写 /api/perf/hello → /api/example/hello
        Route fixedRewriteRoute = createRoute(
                "perf-hello",
                "/api/perf/hello",
                "http://localhost:" + upstreamPort);
        fixedRewriteRoute.setRewritePath("^/api/perf/hello(.*)");
        fixedRewriteRoute.setRewriteReplacement("/api/example/hello$1");

        // 路由3：无重写配置，路径透传
        Route noRewriteRoute = createRoute(
                "example-service",
                "/api/example/**",
                "http://localhost:" + upstreamPort);

        props.setRoutes(List.of(rewriteRoute, fixedRewriteRoute, noRewriteRoute));
        return props;
    }

    @Test
    void prefixRewriteForwardsCorrectPathToUpstream() throws Exception {
        // 客户端请求 /api/perf/route1/hello，上游应收到 /api/example/hello
        HttpRequest request = HttpRequest.newBuilder()
                .uri(gatewayUri("/api/perf/route1/hello"))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(
                request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("Hello from upstream!");

        // 验证上游收到的路径是重写后的路径
        assertThat(upstreamServer.getReceivedRequests()).hasSize(1);
        assertThat(upstreamServer.getReceivedRequests().get(0).uri())
                .isEqualTo("/api/example/hello");
    }

    @Test
    void prefixRewritePreservesQueryString() throws Exception {
        // 客户端请求带 query string，上游应收到重写后路径 + 原始 query string
        HttpRequest request = HttpRequest.newBuilder()
                .uri(gatewayUri("/api/perf/route1/hello?foo=bar"))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(
                request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(upstreamServer.getReceivedRequests()).hasSize(1);
        assertThat(upstreamServer.getReceivedRequests().get(0).uri())
                .isEqualTo("/api/example/hello?foo=bar");
    }

    @Test
    void fixedPathRewriteForwardsCorrectly() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(gatewayUri("/api/perf/hello"))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(
                request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(upstreamServer.getReceivedRequests()).hasSize(1);
        assertThat(upstreamServer.getReceivedRequests().get(0).uri())
                .isEqualTo("/api/example/hello");
    }

    @Test
    void noRewriteRoutePassesThroughOriginalPath() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(gatewayUri("/api/example/hello"))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(
                request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(upstreamServer.getReceivedRequests()).hasSize(1);
        assertThat(upstreamServer.getReceivedRequests().get(0).uri())
                .isEqualTo("/api/example/hello");
    }
}
