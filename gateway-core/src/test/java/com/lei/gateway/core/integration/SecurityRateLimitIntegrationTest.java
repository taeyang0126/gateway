package com.lei.gateway.core.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.lei.gateway.core.config.SecurityProperties;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;

class SecurityRateLimitIntegrationTest extends IntegrationTestBase {

    @Override
    protected SecurityProperties createSecurityProperties() {
        SecurityProperties security = new SecurityProperties();
        security.setEnabled(true);
        security.getRateLimit().getIp().setEnabled(true);
        security.getRateLimit().getIp().setMode(SecurityProperties.RateLimitMode.DISTRIBUTED);
        security.getRateLimit().getIp().setPermitsPerSecond(1);
        security.getRateLimit().getIp().setBurstCapacity(1);
        return security;
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
        assertThat(meterRegistry.get("gateway.security.fallbacks")
                .counter().count()).isGreaterThan(0);
        assertThat(meterRegistry.get("gateway.security.rate_limit.hits")
                .counter().count()).isGreaterThan(0);
    }
}
