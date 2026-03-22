package com.lei.gateway.core.filter;

import java.util.HashMap;
import java.util.Map;

/**
 * 请求级过滤器上下文，生命周期与单次请求一致。
 * 线程安全：所有操作均在 Netty EventLoop 线程上执行，无需额外同步。
 */
public class FilterContext {

    private final Map<String, Object> attributes = new HashMap<>();

    /** 设置属性值。 */
    public void set(String key, Object value) {
        attributes.put(key, value);
    }

    /** 获取属性值，若不存在返回 null。 */
    @SuppressWarnings("unchecked")
    public <T> T get(String key) {
        return (T) attributes.get(key);
    }

    /** 判断属性是否存在。 */
    public boolean contains(String key) {
        return attributes.containsKey(key);
    }

    /** 常用 key 常量。 */
    public static final String USER_ID = "userId";
    public static final String AUTH_RESULT = "authResult";
    public static final String RETRY_ATTEMPT = "retryAttempt";
    /** ScheduledFuture，供超时时取消 RetryFilter 延迟任务。 */
    public static final String RETRY_DELAY_FUTURE = "retryDelayFuture";
    public static final String CIRCUIT_BREAKER_STATE = "circuitBreakerState";
    /** CircuitBreakerFilter 慢调用计时起点（nanoTime）。 */
    public static final String CIRCUIT_BREAKER_START_NS = "circuitBreakerStartNs";
    public static final String IP_ACCESS_RESULT = "ipAccessResult";
    public static final String RATE_LIMITED = "rateLimited";
    /** ConsumeResult，供 post() 写响应头用。 */
    public static final String RATE_LIMIT_RESULT = "rateLimitResult";
}
