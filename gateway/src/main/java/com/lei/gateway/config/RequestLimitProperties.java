package com.lei.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 请求大小限制与超时配置，绑定 {@code gateway.request-limit} 前缀。
 */
@ConfigurationProperties(prefix = "gateway.request-limit")
public class RequestLimitProperties {

    private long maxRequestSize = 50L * 1024 * 1024; // 50MB
    private int timeoutSeconds = 60;
    /** 连接空闲超时（秒），超过此时间无读写则关闭连接，默认 120s。 */
    private int idleTimeoutSeconds = 120;

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

    public int getIdleTimeoutSeconds() {
        return idleTimeoutSeconds;
    }

    public void setIdleTimeoutSeconds(int idleTimeoutSeconds) {
        this.idleTimeoutSeconds = idleTimeoutSeconds;
    }
}
