package com.lei.gateway.security;

/**
 * 限流结果。
 */
public class RateLimitResult {

    private final boolean allowed;
    private final int retryAfterSeconds;

    private RateLimitResult(boolean allowed, int retryAfterSeconds) {
        this.allowed = allowed;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    /**
     * 限流放行结果。
     */
    public static RateLimitResult allowed() {
        return new RateLimitResult(true, 0);
    }

    /**
     * 限流拒绝结果。
     */
    public static RateLimitResult denied(int retryAfterSeconds) {
        return new RateLimitResult(false, Math.max(retryAfterSeconds, 1));
    }

    public boolean isAllowed() {
        return allowed;
    }

    public int getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
