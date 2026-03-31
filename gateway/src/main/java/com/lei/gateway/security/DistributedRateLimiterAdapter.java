package com.lei.gateway.security;

/**
 * 分布式限流适配器占位实现。
 *
 * <p>当前版本仅定义接口位置，运行时由调用方决定是否接入真实后端。
 */
public class DistributedRateLimiterAdapter implements RateLimiterEngine {

    @Override
    public RateLimitResult allow(String key, int permitsPerSecond, int burstCapacity) {
        throw new IllegalStateException("Distributed rate limiter backend is not configured");
    }
}
