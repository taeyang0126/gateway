package com.lei.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;

@SpringBootTest(classes = GatewayPropertiesTest.TestConfig.class)
class GatewayPropertiesTest {

    @Configuration
    @EnableConfigurationProperties({
        GatewayProperties.class,
        RequestLimitProperties.class,
        ConnectionPoolProperties.class,
        ObservabilityProperties.class,
        SecurityProperties.class
    })
    static class TestConfig {
    }

    @Autowired
    private GatewayProperties gatewayProperties;

    @Autowired
    private RequestLimitProperties requestLimitProperties;

    @Autowired
    private ConnectionPoolProperties connectionPoolProperties;

    @Autowired
    private ObservabilityProperties observabilityProperties;
    @Autowired
    private SecurityProperties securityProperties;

    @Test
    void gatewayPortBindsCorrectly() {
        assertThat(gatewayProperties.getPort()).isEqualTo(8080);
    }

    @Test
    void routesBindCorrectly() {
        assertThat(gatewayProperties.getRoutes()).hasSize(1);
        Route route = gatewayProperties.getRoutes().get(0);
        assertThat(route.getId()).isEqualTo("example-service");
        assertThat(route.getPathPrefix()).isEqualTo("/api/example");
        assertThat(route.getUpstream()).isEqualTo("http://localhost:8081");
    }

    @Test
    void routeWithoutOptionalFieldsHasNullValues() {
        Route route = gatewayProperties.getRoutes().get(0);
        assertThat(route.getTimeoutSeconds()).isNull();
        assertThat(route.getMaxRequestSize()).isNull();
    }

    @Test
    void requestLimitBindsCorrectly() {
        assertThat(requestLimitProperties.getMaxRequestSize())
            .isEqualTo(52428800L); // 50MB
        assertThat(requestLimitProperties.getTimeoutSeconds()).isEqualTo(60);
    }

    @Test
    void connectionPoolBindsCorrectly() {
        assertThat(connectionPoolProperties.getMaxConnectionsPerHost()).isEqualTo(50);
        assertThat(connectionPoolProperties.getMaxIdleTimeSeconds()).isEqualTo(60);
        assertThat(connectionPoolProperties.getSlowConnectThresholdMillis()).isEqualTo(50);
        assertThat(connectionPoolProperties.getConnectTimeoutMillis()).isEqualTo(500);
    }

    @Test
    void observabilityBindsCorrectly() {
        assertThat(observabilityProperties.isMetricsEnabled()).isTrue();
        assertThat(observabilityProperties.isAccessLogEnabled()).isTrue();
        assertThat(observabilityProperties.isTracingEnabled()).isTrue();
    }

    @Test
    void securityBindsCorrectly() {
        assertThat(securityProperties.isEnabled()).isTrue();
        assertThat(securityProperties.getTrustedProxies())
                .containsExactly("127.0.0.1/32");
        assertThat(securityProperties.getIpAccess().isEnabled()).isTrue();
        assertThat(securityProperties.getIpAccess().getDenyList())
                .containsExactly("10.0.0.0/8");
        assertThat(securityProperties.getAuth().isEnabled()).isTrue();
        assertThat(securityProperties.getAuth().getType())
                .isEqualTo(SecurityProperties.AuthType.JWT);
        assertThat(securityProperties.getAuth().getProviders().getJwt().getIssuer())
                .isEqualTo("test-issuer");
        assertThat(securityProperties.getAuth().getProviders().getJwt().getAudience())
                .isEqualTo("test-audience");
        assertThat(securityProperties.getAuth()
                .getTokenExtractor().getTokenHeaderName())
                .isEqualTo("Authorization");
        assertThat(securityProperties.getAuth()
                .getTokenExtractor().getTokenValuePrefix())
                .isEqualTo("Bearer ");
        assertThat(securityProperties.getRateLimit().getIp().isEnabled()).isTrue();
        assertThat(securityProperties.getRateLimit().getIp().getPermitsPerSecond())
                .isEqualTo(10);
    }
}
