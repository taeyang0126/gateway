package com.lei.gateway.core.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(
    classes = GatewayAutoConfiguration.class,
    properties = "spring.config.location=classpath:application-empty.yml"
)
@ActiveProfiles("defaults")
class ObservabilityPropertiesDefaultTest {

    @Autowired
    private ObservabilityProperties observabilityProperties;

    @Autowired
    private ConnectionPoolProperties connectionPoolProperties;

    @Test
    void observabilityDefaultValues() {
        assertThat(observabilityProperties.isMetricsEnabled()).isTrue();
        assertThat(observabilityProperties.isAccessLogEnabled()).isTrue();
        assertThat(observabilityProperties.getAccessLogLevel()).isEqualTo("WARN");
        assertThat(observabilityProperties.isTracingEnabled()).isTrue();
    }

    @Test
    void connectionPoolDefaultValues() {
        assertThat(connectionPoolProperties.getConnectTimeoutMillis()).isEqualTo(100);
        assertThat(connectionPoolProperties.getSlowConnectThresholdMillis()).isEqualTo(10);
        assertThat(connectionPoolProperties.getMaxConnectionsPerHost()).isEqualTo(50);
        assertThat(connectionPoolProperties.getMaxIdleTimeSeconds()).isEqualTo(60);
    }
}
