package com.lei.gateway.security;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程内令牌桶限流实现。
 */
public class LocalTokenBucketRateLimiter implements RateLimiterEngine {

    private final ConcurrentHashMap<String, TokenBucket> buckets =
            new ConcurrentHashMap<>();

    @Override
    public RateLimitResult allow(String key, int permitsPerSecond, int burstCapacity) {
        TokenBucket bucket = buckets.computeIfAbsent(key,
                unused -> new TokenBucket(burstCapacity));
        return bucket.tryAcquire(permitsPerSecond, burstCapacity);
    }

    private static final class TokenBucket {

        private double tokens;
        private long lastRefillNanos;

        private TokenBucket(int burstCapacity) {
            this.tokens = burstCapacity;
            this.lastRefillNanos = System.nanoTime();
        }

        private synchronized RateLimitResult tryAcquire(int permitsPerSecond,
                int burstCapacity) {
            long now = System.nanoTime();
            refill(now, permitsPerSecond, burstCapacity);
            if (tokens >= 1.0d) {
                tokens -= 1.0d;
                return RateLimitResult.allowed();
            }
            double missing = 1.0d - tokens;
            int retryAfter = (int) Math.ceil(missing / permitsPerSecond);
            return RateLimitResult.denied(Math.max(retryAfter, 1));
        }

        private void refill(long now, int permitsPerSecond, int burstCapacity) {
            long elapsedNanos = now - lastRefillNanos;
            if (elapsedNanos <= 0) {
                return;
            }
            double refill = (elapsedNanos / 1_000_000_000.0d) * permitsPerSecond;
            tokens = Math.min(burstCapacity, tokens + refill);
            lastRefillNanos = now;
        }
    }
}
