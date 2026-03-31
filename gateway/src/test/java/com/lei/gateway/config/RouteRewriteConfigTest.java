package com.lei.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(classes = RouteRewriteConfigTest.TestConfig.class)
@ActiveProfiles("route-rewrite")
class RouteRewriteConfigTest {

    @Configuration
    @EnableConfigurationProperties(GatewayProperties.class)
    static class TestConfig {
    }

    @Autowired
    private GatewayProperties gatewayProperties;

    @Test
    void routeWithRewriteFieldsIsLoadedCorrectly() {
        Route route = gatewayProperties.getRoutes().stream()
                .filter(r -> "rewrite-route".equals(r.getId()))
                .findFirst().orElseThrow();
        assertThat(route.getRewritePath()).isEqualTo("^/api/perf/route1(.*)");
        assertThat(route.getRewriteReplacement()).isEqualTo("/api/example$1");
    }

    @Test
    void routeWithoutRewriteFieldsReturnsNull() {
        Route route = gatewayProperties.getRoutes().stream()
                .filter(r -> "no-rewrite-route".equals(r.getId()))
                .findFirst().orElseThrow();
        assertThat(route.getRewritePath()).isNull();
        assertThat(route.getRewriteReplacement()).isNull();
    }
}
