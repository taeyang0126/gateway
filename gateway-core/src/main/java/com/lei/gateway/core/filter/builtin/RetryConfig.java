package com.lei.gateway.core.filter.builtin;

import java.util.ArrayList;
import java.util.List;

/**
 * 重试过滤器配置。
 */
public class RetryConfig {

    private int maxAttempts = 3;
    private List<Integer> retryOnStatus = new ArrayList<>(List.of(502, 503, 504));
    private boolean retryOnConnectFailure = true;
    /** 固定重试间隔（毫秒），不支持退避策略。 */
    private int retryDelayMs = 0;
    /** 额外允许重试的 HTTP 方法（PUT/DELETE）。 */
    private List<String> retryOnMethods = new ArrayList<>();

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public List<Integer> getRetryOnStatus() {
        return retryOnStatus;
    }

    public void setRetryOnStatus(List<Integer> retryOnStatus) {
        this.retryOnStatus = retryOnStatus;
    }

    public boolean isRetryOnConnectFailure() {
        return retryOnConnectFailure;
    }

    public void setRetryOnConnectFailure(boolean retryOnConnectFailure) {
        this.retryOnConnectFailure = retryOnConnectFailure;
    }

    public int getRetryDelayMs() {
        return retryDelayMs;
    }

    public void setRetryDelayMs(int retryDelayMs) {
        this.retryDelayMs = retryDelayMs;
    }

    public List<String> getRetryOnMethods() {
        return retryOnMethods;
    }

    public void setRetryOnMethods(List<String> retryOnMethods) {
        this.retryOnMethods = retryOnMethods;
    }
}
