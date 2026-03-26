package com.lei.gateway.core.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ShutdownPropertiesTest {

    @Test
    void defaultValues() {
        ShutdownProperties props = new ShutdownProperties();
        assertThat(props.getShutdownTimeoutSeconds()).isEqualTo(30);
        assertThat(props.getShutdownPollIntervalMillis()).isEqualTo(500);
    }
}
