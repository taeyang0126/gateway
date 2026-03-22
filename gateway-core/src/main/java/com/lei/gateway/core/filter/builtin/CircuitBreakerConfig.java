package com.lei.gateway.core.filter.builtin;

/**
 * 熔断过滤器配置。
 */
public class CircuitBreakerConfig {

    private double failureRateThreshold = 50.0;
    /** 100% = 不启用慢调用熔断。 */
    private double slowCallRateThreshold = 100.0;
    /** 0 = 不启用慢调用检测。 */
    private int slowCallDurationThresholdMs = 0;
    private int minimumNumberOfCalls = 10;
    private SlidingWindowType slidingWindowType = SlidingWindowType.COUNT_BASED;
    private int slidingWindowSize = 10;
    /** OPEN 状态持续时间（秒）。 */
    private int waitDurationInOpenState = 30;
    private int permittedCallsInHalfOpenState = 3;

    public double getFailureRateThreshold() {
        return failureRateThreshold;
    }

    public void setFailureRateThreshold(double failureRateThreshold) {
        this.failureRateThreshold = failureRateThreshold;
    }

    public double getSlowCallRateThreshold() {
        return slowCallRateThreshold;
    }

    public void setSlowCallRateThreshold(double slowCallRateThreshold) {
        this.slowCallRateThreshold = slowCallRateThreshold;
    }

    public int getSlowCallDurationThresholdMs() {
        return slowCallDurationThresholdMs;
    }

    public void setSlowCallDurationThresholdMs(int slowCallDurationThresholdMs) {
        this.slowCallDurationThresholdMs = slowCallDurationThresholdMs;
    }

    public int getMinimumNumberOfCalls() {
        return minimumNumberOfCalls;
    }

    public void setMinimumNumberOfCalls(int minimumNumberOfCalls) {
        this.minimumNumberOfCalls = minimumNumberOfCalls;
    }

    public SlidingWindowType getSlidingWindowType() {
        return slidingWindowType;
    }

    public void setSlidingWindowType(SlidingWindowType slidingWindowType) {
        this.slidingWindowType = slidingWindowType;
    }

    public int getSlidingWindowSize() {
        return slidingWindowSize;
    }

    public void setSlidingWindowSize(int slidingWindowSize) {
        this.slidingWindowSize = slidingWindowSize;
    }

    public int getWaitDurationInOpenState() {
        return waitDurationInOpenState;
    }

    public void setWaitDurationInOpenState(int waitDurationInOpenState) {
        this.waitDurationInOpenState = waitDurationInOpenState;
    }

    public int getPermittedCallsInHalfOpenState() {
        return permittedCallsInHalfOpenState;
    }

    public void setPermittedCallsInHalfOpenState(int permittedCallsInHalfOpenState) {
        this.permittedCallsInHalfOpenState = permittedCallsInHalfOpenState;
    }

    /** 滑动窗口类型。 */
    public enum SlidingWindowType {
        /** 基于请求次数的滑动窗口。 */
        COUNT_BASED,
        /** 基于时间的滑动窗口。 */
        TIME_BASED
    }
}
