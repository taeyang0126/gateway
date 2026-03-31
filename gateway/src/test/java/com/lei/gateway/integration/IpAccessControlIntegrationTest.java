package com.lei.gateway.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.lei.gateway.config.GatewayProperties;
import com.lei.gateway.config.PluginConfigEntry;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class IpAccessControlIntegrationTest extends IntegrationTestBase {

    @Override
    protected GatewayProperties createGatewayProperties() {
        GatewayProperties props = super.createGatewayProperties();
        PluginConfigEntry realIp = new PluginConfigEntry();
        realIp.setName("real-ip");
        PluginConfigEntry ipAccess = new PluginConfigEntry();
        ipAccess.setName("ip-access");
        ipAccess.setConfig(Map.of("deny-list", List.of("127.0.0.1/32")));
        props.setPlugins(List.of(realIp, ipAccess));
        return props;
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
