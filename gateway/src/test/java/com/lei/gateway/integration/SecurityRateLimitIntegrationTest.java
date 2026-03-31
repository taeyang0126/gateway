package com.lei.gateway.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.lei.gateway.config.GatewayProperties;
import com.lei.gateway.config.PluginConfigEntry;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SecurityRateLimitIntegrationTest extends IntegrationTestBase {

    @Override
    protected GatewayProperties createGatewayProperties() {
        GatewayProperties props = super.createGatewayProperties();
        PluginConfigEntry realIp = new PluginConfigEntry();
        realIp.setName("real-ip");
        PluginConfigEntry ipRateLimit = new PluginConfigEntry();
        ipRateLimit.setName("ip-rate-limit");
        ipRateLimit.setConfig(Map.of(
                "permits-per-second", 1,
                "burst-capacity", 1));
        props.setPlugins(List.of(realIp, ipRateLimit));
        return props;
    }

    @Test
    void secondRequestShouldReturn429() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(gatewayUri("/api/example/hello"))
                .GET()
                .build();

        HttpResponse<String> first = httpClient.send(
                request, HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> second = httpClient.send(
                request, HttpResponse.BodyHandlers.ofString());

        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(second.statusCode()).isEqualTo(429);
        assertThat(second.headers().firstValue("Retry-After")).isPresent();
        assertThat(meterRegistry.get("gateway.plugin.decisions")
                .tag("plugin", "ip-rate-limit")
                .tag("decision", "SHORT_CIRCUIT")
                .counter().count()).isGreaterThan(0);
    }
}
