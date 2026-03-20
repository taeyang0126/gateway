package com.example.gateway.core.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Upstream 连接池配置，绑定 {@code gateway.connection-pool} 前缀。
 */
@ConfigurationProperties(prefix = "gateway.connection-pool")
public class ConnectionPoolProperties {

    private int maxConnectionsPerHost = 50;
    private int maxIdleTimeSeconds = 60;
    private int slowConnectThresholdMillis = 50;
    private int connectTimeoutMillis = 500;

    public int getMaxConnectionsPerHost() {
        return maxConnectionsPerHost;
    }

    public void setMaxConnectionsPerHost(int maxConnectionsPerHost) {
        this.maxConnectionsPerHost = maxConnectionsPerHost;
    }

    public int getMaxIdleTimeSeconds() {
        return maxIdleTimeSeconds;
    }

    public void setMaxIdleTimeSeconds(int maxIdleTimeSeconds) {
        this.maxIdleTimeSeconds = maxIdleTimeSeconds;
    }

    public int getSlowConnectThresholdMillis() {
        return slowConnectThresholdMillis;
    }

    public void setSlowConnectThresholdMillis(int slowConnectThresholdMillis) {
        this.slowConnectThresholdMillis = slowConnectThresholdMillis;
    }

    public int getConnectTimeoutMillis() {
        return connectTimeoutMillis;
    }

    public void setConnectTimeoutMillis(int connectTimeoutMillis) {
        this.connectTimeoutMillis = connectTimeoutMillis;
    }
}
