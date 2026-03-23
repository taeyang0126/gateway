package com.lei.gateway.core.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;

class InvalidConfigTest {

    @Test
    void invalidRouteConfigFailsStartup() {
        try {
            new SpringApplicationBuilder(GatewayAutoConfiguration.class)
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
