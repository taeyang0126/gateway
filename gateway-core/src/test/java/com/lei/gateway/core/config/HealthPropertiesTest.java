package com.lei.gateway.core.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class HealthPropertiesTest {

    @Test
    void defaultValues() {
        HealthProperties props = new HealthProperties();
        assertThat(props.getStartupDelaySeconds()).isEqualTo(0);
        assertThat(props.getWarmupTimeoutSeconds()).isEqualTo(10);
    }
}
