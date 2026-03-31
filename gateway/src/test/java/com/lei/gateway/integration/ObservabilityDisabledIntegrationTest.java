package com.lei.gateway.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.lei.gateway.config.ObservabilityProperties;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;

/**
 * 可观测性开关关闭时的集成测试。
 *
 * <p>验证 metricsEnabled=false 时 /metrics 返回 404，
 * tracingEnabled=false 时不添加 traceparent。
 */
class ObservabilityDisabledIntegrationTest
        extends IntegrationTestBase {

    @Override
    protected ObservabilityProperties
            createObservabilityProperties() {
        ObservabilityProperties props =
                new ObservabilityProperties();
        props.setMetricsEnabled(false);
        props.setAccessLogEnabled(false);
        props.setTracingEnabled(false);
        return props;
    }

    @Test
    void metricsDisabledReturns404() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(gatewayUri("/metrics"))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(
                request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(404);
    }

    @Test
    void tracingDisabledNoTraceparentToUpstream()
            throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(gatewayUri("/api/example/hello"))
                .GET()
                .build();

        httpClient.send(request,
                HttpResponse.BodyHandlers.ofString());

        var received = upstreamServer.getReceivedRequests()
                .get(0);
        // tracingEnabled=false 时不应添加 traceparent
        assertThat(received.headers().get("traceparent"))
                .isNull();
    }
}
