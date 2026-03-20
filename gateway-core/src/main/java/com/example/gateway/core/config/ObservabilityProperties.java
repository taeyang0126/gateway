package com.example.gateway.core.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 可观测性配置，绑定 {@code gateway.observability} 前缀。
 */
@ConfigurationProperties(prefix = "gateway.observability")
public class ObservabilityProperties {

    private boolean metricsEnabled = true;
    private boolean accessLogEnabled = true;
    private String accessLogLevel = "WARN";
    private boolean tracingEnabled = true;

    public boolean isMetricsEnabled() {
        return metricsEnabled;
    }

    public void setMetricsEnabled(boolean metricsEnabled) {
        this.metricsEnabled = metricsEnabled;
    }

    public boolean isAccessLogEnabled() {
        return accessLogEnabled;
    }

    public void setAccessLogEnabled(boolean accessLogEnabled) {
        this.accessLogEnabled = accessLogEnabled;
    }

    public String getAccessLogLevel() {
        return accessLogLevel;
    }

    public void setAccessLogLevel(String accessLogLevel) {
        this.accessLogLevel = accessLogLevel;
    }

    public boolean isTracingEnabled() {
        return tracingEnabled;
    }

    public void setTracingEnabled(boolean tracingEnabled) {
        this.tracingEnabled = tracingEnabled;
    }
}
