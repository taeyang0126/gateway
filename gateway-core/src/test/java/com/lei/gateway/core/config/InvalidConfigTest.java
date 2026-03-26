package com.lei.gateway.core.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

class InvalidConfigTest {

    @Configuration
    @EnableConfigurationProperties(GatewayProperties.class)
    static class TestConfig {
    }

    @Test
    void invalidRouteConfigFailsStartup() {
        try {
            new SpringApplicationBuilder(TestConfig.class)
                .profiles("invalid")
                .run();
            throw new AssertionError("Expected startup to fail");
        } catch (Exception ex) {
            assertThat(findRootCause(ex).getMessage())
                .containsAnyOf("Binding validation errors", "不能为空",
                    "must not be blank");
        }
    }

    private Throwable findRootCause(Throwable t) {
        Throwable cause = t;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause;
    }
}
