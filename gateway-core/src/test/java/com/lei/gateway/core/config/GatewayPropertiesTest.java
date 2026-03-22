package com.lei.gateway.core.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(classes = GatewayAutoConfiguration.class)
class GatewayPropertiesTest {

    @Autowired
    private GatewayProperties gatewayProperties;

    @Autowired
    private RequestLimitProperties requestLimitProperties;

    @Autowired
    private ConnectionPoolProperties connectionPoolProperties;

    @Autowired
    private ObservabilityProperties observabilityProperties;

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
}
