package com.lei.gateway.core.filter.builtin;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * builtin 配置 POJO 单元测试：验证默认值、getter/setter、枚举。
 */
class BuiltinConfigTest {

    // ---- AuthConfig ----

    @Test
    void authConfig_defaults() {
        AuthConfig cfg = new AuthConfig();
        assertThat(cfg.isEnabled()).isFalse();
        assertThat(cfg.getType()).isEqualTo(AuthConfig.AuthType.JWT);
        assertThat(cfg.getAuthServiceUrl()).isNull();
        assertThat(cfg.getAuthServiceTimeoutMs()).isEqualTo(3000);
        assertThat(cfg.getAuthCacheTtlSeconds()).isEqualTo(0);
    }

    @Test
    void authConfig_setters() {
        AuthConfig cfg = new AuthConfig();
        cfg.setEnabled(true);
        cfg.setType(AuthConfig.AuthType.JWT);
        cfg.setAuthServiceUrl("http://auth.svc");
        cfg.setAuthServiceTimeoutMs(5000);
        cfg.setAuthCacheTtlSeconds(60);

        assertThat(cfg.isEnabled()).isTrue();
        assertThat(cfg.getType()).isEqualTo(AuthConfig.AuthType.JWT);
        assertThat(cfg.getAuthServiceUrl()).isEqualTo("http://auth.svc");
        assertThat(cfg.getAuthServiceTimeoutMs()).isEqualTo(5000);
        assertThat(cfg.getAuthCacheTtlSeconds()).isEqualTo(60);
    }

    // ---- CircuitBreakerConfig ----

    @Test
    void circuitBreakerConfig_defaults() {
        CircuitBreakerConfig cfg = new CircuitBreakerConfig();
        assertThat(cfg.getFailureRateThreshold()).isEqualTo(50.0);
        assertThat(cfg.getSlowCallRateThreshold()).isEqualTo(100.0);
        assertThat(cfg.getSlowCallDurationThresholdMs()).isEqualTo(0);
        assertThat(cfg.getMinimumNumberOfCalls()).isEqualTo(10);
        assertThat(cfg.getSlidingWindowType())
                .isEqualTo(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED);
        assertThat(cfg.getSlidingWindowSize()).isEqualTo(10);
        assertThat(cfg.getWaitDurationInOpenState()).isEqualTo(30);
        assertThat(cfg.getPermittedCallsInHalfOpenState()).isEqualTo(3);
    }

    @Test
    void circuitBreakerConfig_setters() {
        CircuitBreakerConfig cfg = new CircuitBreakerConfig();
        cfg.setFailureRateThreshold(80.0);
        cfg.setSlowCallRateThreshold(60.0);
        cfg.setSlowCallDurationThresholdMs(2000);
        cfg.setMinimumNumberOfCalls(5);
        cfg.setSlidingWindowType(CircuitBreakerConfig.SlidingWindowType.TIME_BASED);
        cfg.setSlidingWindowSize(20);
        cfg.setWaitDurationInOpenState(60);
        cfg.setPermittedCallsInHalfOpenState(5);

        assertThat(cfg.getFailureRateThreshold()).isEqualTo(80.0);
        assertThat(cfg.getSlowCallRateThreshold()).isEqualTo(60.0);
        assertThat(cfg.getSlowCallDurationThresholdMs()).isEqualTo(2000);
        assertThat(cfg.getMinimumNumberOfCalls()).isEqualTo(5);
        assertThat(cfg.getSlidingWindowType())
                .isEqualTo(CircuitBreakerConfig.SlidingWindowType.TIME_BASED);
        assertThat(cfg.getSlidingWindowSize()).isEqualTo(20);
        assertThat(cfg.getWaitDurationInOpenState()).isEqualTo(60);
        assertThat(cfg.getPermittedCallsInHalfOpenState()).isEqualTo(5);
    }

    // ---- HeaderTransformConfig ----

    @Test
    void headerTransformConfig_defaults() {
        HeaderTransformConfig cfg = new HeaderTransformConfig();
        assertThat(cfg.getRequest()).isNotNull();
        assertThat(cfg.getResponse()).isNotNull();
        assertThat(cfg.getRequest().getAdd()).isEmpty();
        assertThat(cfg.getRequest().getSet()).isEmpty();
        assertThat(cfg.getRequest().getRemove()).isEmpty();
    }

    @Test
    void headerTransformConfig_setters() {
        HeaderTransformConfig cfg = new HeaderTransformConfig();

        HeaderTransformConfig.HeaderEntry entry = new HeaderTransformConfig.HeaderEntry();
        entry.setName("X-Custom");
        entry.setValue("value1");
        assertThat(entry.getName()).isEqualTo("X-Custom");
        assertThat(entry.getValue()).isEqualTo("value1");

        HeaderTransformConfig.HeaderRules rules = new HeaderTransformConfig.HeaderRules();
        rules.setAdd(List.of(entry));
        rules.setSet(List.of(entry));
        rules.setRemove(List.of("X-Remove"));

        cfg.setRequest(rules);
        cfg.setResponse(rules);

        assertThat(cfg.getRequest().getAdd()).hasSize(1);
        assertThat(cfg.getRequest().getSet()).hasSize(1);
        assertThat(cfg.getRequest().getRemove()).containsExactly("X-Remove");
        assertThat(cfg.getResponse()).isSameAs(rules);
    }

    // ---- IpAccessControlConfig ----

    @Test
    void ipAccessControlConfig_defaults() {
        IpAccessControlConfig cfg = new IpAccessControlConfig();
        assertThat(cfg.getMode()).isNull();
        assertThat(cfg.getRules()).isEmpty();
    }

    @Test
    void ipAccessControlConfig_setters() {
        IpAccessControlConfig cfg = new IpAccessControlConfig();
        cfg.setMode(IpAccessControlConfig.Mode.ALLOWLIST);
        cfg.setRules(List.of("192.168.1.0/24", "10.0.0.1"));

        assertThat(cfg.getMode()).isEqualTo(IpAccessControlConfig.Mode.ALLOWLIST);
        assertThat(cfg.getRules()).containsExactly("192.168.1.0/24", "10.0.0.1");

        cfg.setMode(IpAccessControlConfig.Mode.DENYLIST);
        assertThat(cfg.getMode()).isEqualTo(IpAccessControlConfig.Mode.DENYLIST);
    }

    // ---- RateLimitConfig ----

    @Test
    void rateLimitConfig_defaults() {
        RateLimitConfig cfg = new RateLimitConfig();
        assertThat(cfg.getDimensions()).isEmpty();
    }

    @Test
    void rateLimitConfig_dimensionConfig() {
        RateLimitConfig.DimensionConfig dim = new RateLimitConfig.DimensionConfig();
        assertThat(dim.getBurstCapacity()).isEqualTo(-1);
        assertThat(dim.getDimension()).isNull();

        dim.setDimension(RateLimitConfig.DimensionConfig.Dimension.IP);
        dim.setLimit(100);
        dim.setWindowSeconds(60);
        dim.setBurstCapacity(200);

        assertThat(dim.getDimension()).isEqualTo(RateLimitConfig.DimensionConfig.Dimension.IP);
        assertThat(dim.getLimit()).isEqualTo(100);
        assertThat(dim.getWindowSeconds()).isEqualTo(60);
        assertThat(dim.getBurstCapacity()).isEqualTo(200);

        // 覆盖其他枚举值
        dim.setDimension(RateLimitConfig.DimensionConfig.Dimension.ROUTE);
        assertThat(dim.getDimension()).isEqualTo(RateLimitConfig.DimensionConfig.Dimension.ROUTE);
        dim.setDimension(RateLimitConfig.DimensionConfig.Dimension.USER_ID);
        assertThat(dim.getDimension()).isEqualTo(RateLimitConfig.DimensionConfig.Dimension.USER_ID);

        RateLimitConfig cfg = new RateLimitConfig();
        cfg.setDimensions(List.of(dim));
        assertThat(cfg.getDimensions()).hasSize(1);
    }

    // ---- RetryConfig ----

    @Test
    void retryConfig_defaults() {
        RetryConfig cfg = new RetryConfig();
        assertThat(cfg.getMaxAttempts()).isEqualTo(3);
        assertThat(cfg.getRetryOnStatus()).containsExactly(502, 503, 504);
        assertThat(cfg.isRetryOnConnectFailure()).isTrue();
        assertThat(cfg.getRetryDelayMs()).isEqualTo(0);
        assertThat(cfg.getRetryOnMethods()).isEmpty();
    }

    @Test
    void retryConfig_setters() {
        RetryConfig cfg = new RetryConfig();
        cfg.setMaxAttempts(5);
        cfg.setRetryOnStatus(List.of(500, 502));
        cfg.setRetryOnConnectFailure(false);
        cfg.setRetryDelayMs(100);
        cfg.setRetryOnMethods(List.of("PUT", "DELETE"));

        assertThat(cfg.getMaxAttempts()).isEqualTo(5);
        assertThat(cfg.getRetryOnStatus()).containsExactly(500, 502);
        assertThat(cfg.isRetryOnConnectFailure()).isFalse();
        assertThat(cfg.getRetryDelayMs()).isEqualTo(100);
        assertThat(cfg.getRetryOnMethods()).containsExactly("PUT", "DELETE");
    }
}
