package com.example.gateway.pool;

/**
 * {@link ConcurrentPool} 的配置类。
 */
public class PoolConfig {

    private int maxPoolSize = 50;
    private int maxIdleTimeSeconds = 60;
    private int connectionTimeoutMillis = 500;
    private int threadLocalCacheSize = 32;

    public int getMaxPoolSize() {
        return maxPoolSize;
    }

    public void setMaxPoolSize(int maxPoolSize) {
        this.maxPoolSize = maxPoolSize;
    }

    public int getMaxIdleTimeSeconds() {
        return maxIdleTimeSeconds;
    }

    public void setMaxIdleTimeSeconds(int maxIdleTimeSeconds) {
        this.maxIdleTimeSeconds = maxIdleTimeSeconds;
    }

    public int getConnectionTimeoutMillis() {
        return connectionTimeoutMillis;
    }

    public void setConnectionTimeoutMillis(int connectionTimeoutMillis) {
        this.connectionTimeoutMillis = connectionTimeoutMillis;
    }

    public int getThreadLocalCacheSize() {
        return threadLocalCacheSize;
    }

    public void setThreadLocalCacheSize(int threadLocalCacheSize) {
        this.threadLocalCacheSize = threadLocalCacheSize;
    }
}
