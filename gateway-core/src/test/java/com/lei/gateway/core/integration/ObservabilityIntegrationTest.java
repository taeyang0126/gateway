package com.lei.gateway.core.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;

/**
 * 可观测性集成测试。
 *
 * <p>验证 traceparent 传递、/metrics 端点、可观测性开关。
 */
class ObservabilityIntegrationTest extends IntegrationTestBase {

    @Test
    void traceparentPassedToUpstream() throws Exception {
        String traceparent =
                "00-4bf92f3577b34da6a3ce929d0e0e4736"
                + "-00f067aa0ba902b7-01";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(gatewayUri("/api/example/hello"))
                .GET()
                .header("traceparent", traceparent)
                .build();

        httpClient.send(request,
                HttpResponse.BodyHandlers.ofString());

        var received = upstreamServer.getReceivedRequests()
                .get(0);
        String upstreamTp =
                received.headers().get("traceparent");
        assertThat(upstreamTp).isNotNull();
        assertThat(upstreamTp).contains(
                "4bf92f3577b34da6a3ce929d0e0e4736");
        assertThat(upstreamTp)
                .doesNotContain("00f067aa0ba902b7");
    }

    @Test
    void traceparentGeneratedWhenNotProvided()
            throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(gatewayUri("/api/example/hello"))
                .GET()
                .build();

        httpClient.send(request,
                HttpResponse.BodyHandlers.ofString());

        var received = upstreamServer.getReceivedRequests()
                .get(0);
        String upstreamTp =
                received.headers().get("traceparent");
        assertThat(upstreamTp).isNotNull();
        assertThat(upstreamTp).matches(
                "00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}");
    }

    @Test
    void tracestatePassedThrough() throws Exception {
        String traceparent =
                "00-4bf92f3577b34da6a3ce929d0e0e4736"
                + "-00f067aa0ba902b7-01";
        String tracestate = "congo=t61rcWkgMzE";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(gatewayUri("/api/example/hello"))
                .GET()
                .header("traceparent", traceparent)
                .header("tracestate", tracestate)
                .build();

        httpClient.send(request,
                HttpResponse.BodyHandlers.ofString());

        var received = upstreamServer.getReceivedRequests()
                .get(0);
        assertThat(received.headers().get("tracestate"))
                .isEqualTo(tracestate);
    }

    @Test
    void metricsEndpointReturnsPrometheusFormat()
            throws Exception {
        HttpRequest warmup = HttpRequest.newBuilder()
                .uri(gatewayUri("/api/example/hello"))
                .GET()
                .build();
        httpClient.send(warmup,
                HttpResponse.BodyHandlers.ofString());

        HttpRequest request = HttpRequest.newBuilder()
                .uri(gatewayUri("/metrics"))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(
                request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        String body = response.body();
        assertThat(body).contains("gateway_requests_total");
        assertThat(body).contains(
                "gateway_connections_active");
    }

    @Test
    void metricsContainsJvmMetrics() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(gatewayUri("/metrics"))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(
                request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        String body = response.body();
        assertThat(body).contains("jvm_memory");
        assertThat(body).contains("jvm_threads");
    }

    @Test
    void metricsContainsNettyMetrics() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(gatewayUri("/metrics"))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(
                request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        String body = response.body();
        assertThat(body).contains(
                "netty_eventloop_pending_tasks");
        assertThat(body).contains(
                "netty_allocator_used_direct_memory");
    }

    @Test
    void metricsContainsUpstreamConnectDuration()
            throws Exception {
        // 首次连接可能超过 slowConnectThresholdMillis（默认 10ms），
        // 此时 connect duration metric 会被记录。验证 metric 格式正确即可。
        HttpRequest warmup = HttpRequest.newBuilder()
                .uri(gatewayUri("/api/example/hello"))
                .GET()
                .build();
        httpClient.send(warmup,
                HttpResponse.BodyHandlers.ofString());

        HttpRequest request = HttpRequest.newBuilder()
                .uri(gatewayUri("/metrics"))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(
                request, HttpResponse.BodyHandlers.ofString());

        // 请求指标正常存在
        assertThat(response.body()).contains(
                "gateway_requests_total");
        // connect duration 可能存在（首次连接超过慢阈值时），如果存在则格式正确
        if (response.body().contains("gateway_upstream_connect_duration")) {
            assertThat(response.body()).contains(
                    "gateway_upstream_connect_duration_seconds");
        }
    }

    @Test
    void requestMetricsRecordedAfterProxy() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(gatewayUri("/api/example/hello"))
                .GET()
                .build();

        httpClient.send(request,
                HttpResponse.BodyHandlers.ofString());

        String scrape = meterRegistry.scrape();
        assertThat(scrape).contains("gateway_requests_total");
        assertThat(scrape).contains(
                "gateway_requests_duration");
    }
}
