package com.lei.gateway.core.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.lei.gateway.core.config.SecurityProperties;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;

class IpAllowlistEnforcementIntegrationTest extends IntegrationTestBase {

    @Override
    protected SecurityProperties createSecurityProperties() {
        SecurityProperties security = new SecurityProperties();
        security.setEnabled(true);
        security.getIpAccess().setEnabled(true);
        // allowList 不包含 127.0.0.1，验证未命中白名单时拒绝。
        security.getIpAccess().setAllowList(java.util.List.of("10.0.0.0/8"));
        return security;
    }

    @Test
    void requestNotInAllowListShouldReturn403() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(gatewayUri("/api/example/hello"))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(
                request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.body()).contains("ip_not_in_allow_list");
        assertThat(upstreamServer.getReceivedRequests()).isEmpty();
    }
}
