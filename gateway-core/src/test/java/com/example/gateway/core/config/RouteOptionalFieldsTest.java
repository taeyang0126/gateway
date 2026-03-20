package com.example.gateway.core.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(classes = GatewayAutoConfiguration.class)
@ActiveProfiles("route-optional")
class RouteOptionalFieldsTest {

    @Autowired
    private GatewayProperties gatewayProperties;

    @Test
    void routeWithTimeoutAndMaxRequestSize() {
        Route route = gatewayProperties.getRoutes().stream()
            .filter(r -> "with-timeout".equals(r.getId()))
            .findFirst().orElseThrow();
        assertThat(route.getTimeoutSeconds()).isEqualTo(120);
        assertThat(route.getMaxRequestSize()).isEqualTo(104857600L);
    }

    @Test
    void routeWithoutTimeoutAndMaxRequestSize() {
        Route route = gatewayProperties.getRoutes().stream()
            .filter(r -> "without-timeout".equals(r.getId()))
            .findFirst().orElseThrow();
        assertThat(route.getTimeoutSeconds()).isNull();
        assertThat(route.getMaxRequestSize()).isNull();
    }
}
