package com.lei.gateway.core.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.lei.gateway.core.config.SecurityProperties;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;

class IpAccessControlIntegrationTest extends IntegrationTestBase {

    @Override
    protected SecurityProperties createSecurityProperties() {
        SecurityProperties security = new SecurityProperties();
        security.setEnabled(true);
        security.getIpAccess().setEnabled(true);
        security.getIpAccess().setDenyList(java.util.List.of("127.0.0.1/32"));
        return security;
    }

    @Test
    void deniedIpShouldReturn403() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(gatewayUri("/api/example/hello"))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(
                request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.body()).contains("ip_in_deny_list");
        assertThat(upstreamServer.getReceivedRequests()).isEmpty();
    }
}
