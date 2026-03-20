package com.example.gateway.core.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 请求大小限制与超时配置，绑定 {@code gateway.request-limit} 前缀。
 */
@ConfigurationProperties(prefix = "gateway.request-limit")
public class RequestLimitProperties {

    private long maxRequestSize = 50L * 1024 * 1024; // 50MB
    private int timeoutSeconds = 60;

    public long getMaxRequestSize() {
        return maxRequestSize;
    }

    public void setMaxRequestSize(long maxRequestSize) {
        this.maxRequestSize = maxRequestSize;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }
}
