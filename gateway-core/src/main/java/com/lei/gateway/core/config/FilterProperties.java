package com.lei.gateway.core.config;

import com.lei.gateway.core.filter.builtin.AuthConfig;
import com.lei.gateway.core.filter.builtin.CircuitBreakerConfig;
import com.lei.gateway.core.filter.builtin.HeaderTransformConfig;
import com.lei.gateway.core.filter.builtin.IpAccessControlConfig;
import com.lei.gateway.core.filter.builtin.RateLimitConfig;
import com.lei.gateway.core.filter.builtin.RetryConfig;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 过滤器链全局配置，绑定 {@code gateway.filters} 前缀。
 */
@ConfigurationProperties(prefix = "gateway.filters")
public class FilterProperties {

    /** 过滤器链总超时（毫秒），0 = 不限制。 */
    private long filterChainTimeoutMs = 0;

    /** 全局默认过滤器列表（按执行顺序）。 */
    private List<String> defaultFilters = new ArrayList<>();

    /** 全局默认各过滤器配置（路由级配置优先覆盖）。 */
    private IpAccessControlConfig ipAccessControl;
    private AuthConfig auth;
    /** 认证前限流全局默认配置。 */
    private RateLimitConfig preAuthRateLimit;
    /** 认证后限流全局默认配置。 */
    private RateLimitConfig postAuthRateLimit;
    private HeaderTransformConfig headerTransform;
    private RetryConfig retry;
    private CircuitBreakerConfig circuitBreaker;

    public long getFilterChainTimeoutMs() {
        return filterChainTimeoutMs;
    }

    public void setFilterChainTimeoutMs(long filterChainTimeoutMs) {
        this.filterChainTimeoutMs = filterChainTimeoutMs;
    }

    public List<String> getDefaultFilters() {
        return defaultFilters;
    }

    public void setDefaultFilters(List<String> defaultFilters) {
        this.defaultFilters = defaultFilters;
    }

    public IpAccessControlConfig getIpAccessControl() {
        return ipAccessControl;
    }

    public void setIpAccessControl(IpAccessControlConfig ipAccessControl) {
        this.ipAccessControl = ipAccessControl;
    }

    public AuthConfig getAuth() {
        return auth;
    }

    public void setAuth(AuthConfig auth) {
        this.auth = auth;
    }

    public RateLimitConfig getPreAuthRateLimit() {
        return preAuthRateLimit;
    }

    public void setPreAuthRateLimit(RateLimitConfig preAuthRateLimit) {
        this.preAuthRateLimit = preAuthRateLimit;
    }

    public RateLimitConfig getPostAuthRateLimit() {
        return postAuthRateLimit;
    }

    public void setPostAuthRateLimit(RateLimitConfig postAuthRateLimit) {
        this.postAuthRateLimit = postAuthRateLimit;
    }

    public HeaderTransformConfig getHeaderTransform() {
        return headerTransform;
    }

    public void setHeaderTransform(HeaderTransformConfig headerTransform) {
        this.headerTransform = headerTransform;
    }

    public RetryConfig getRetry() {
        return retry;
    }

    public void setRetry(RetryConfig retry) {
        this.retry = retry;
    }

    public CircuitBreakerConfig getCircuitBreaker() {
        return circuitBreaker;
    }

    public void setCircuitBreaker(CircuitBreakerConfig circuitBreaker) {
        this.circuitBreaker = circuitBreaker;
    }
}
