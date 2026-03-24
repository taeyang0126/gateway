package com.lei.gateway.core.security;

/**
 * 限流引擎接口。
 */
public interface RateLimiterEngine {

    /**
     * 尝试消费一个请求令牌。
     *
     * @param key               限流键
     * @param permitsPerSecond  每秒令牌数
     * @param burstCapacity     桶容量
     * @return 限流结果
     */
    RateLimitResult allow(String key, int permitsPerSecond, int burstCapacity);
}
