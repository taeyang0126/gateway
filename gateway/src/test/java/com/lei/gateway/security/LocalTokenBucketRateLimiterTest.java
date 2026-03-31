package com.lei.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LocalTokenBucketRateLimiterTest {

    @Test
    void shouldDenyWhenBurstExhausted() {
        LocalTokenBucketRateLimiter limiter = new LocalTokenBucketRateLimiter();
        String key = "route-a:ip:127.0.0.1";

        RateLimitResult first = limiter.allow(key, 1, 1);
        RateLimitResult second = limiter.allow(key, 1, 1);

        assertThat(first.isAllowed()).isTrue();
        assertThat(second.isAllowed()).isFalse();
        assertThat(second.getRetryAfterSeconds()).isGreaterThanOrEqualTo(1);
    }
}
