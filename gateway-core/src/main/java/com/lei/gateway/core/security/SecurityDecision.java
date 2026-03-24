package com.lei.gateway.core.security;

import io.netty.handler.codec.http.HttpResponseStatus;

/**
 * 安全过滤决策结果。
 */
public class SecurityDecision {

    private final SecurityDecisionType type;
    private final HttpResponseStatus status;
    private final String filterName;
    private final String reason;
    private final Integer retryAfterSeconds;

    private SecurityDecision(SecurityDecisionType type,
            HttpResponseStatus status, String filterName, String reason,
            Integer retryAfterSeconds) {
        this.type = type;
        this.status = status;
        this.filterName = filterName;
        this.reason = reason;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    /**
     * 生成放行决策。
     */
    public static SecurityDecision allow(String filterName, String reason) {
        return new SecurityDecision(SecurityDecisionType.ALLOW,
                HttpResponseStatus.OK, filterName, reason, null);
    }

    /**
     * 生成拒绝决策。
     */
    public static SecurityDecision deny(HttpResponseStatus status,
            String filterName, String reason) {
        return new SecurityDecision(SecurityDecisionType.DENY,
                status, filterName, reason, null);
    }

    /**
     * 生成包含 Retry-After 的拒绝决策。
     */
    public static SecurityDecision denyWithRetry(HttpResponseStatus status,
            String filterName, String reason, int retryAfterSeconds) {
        return new SecurityDecision(SecurityDecisionType.DENY,
                status, filterName, reason, retryAfterSeconds);
    }

    /**
     * 生成错误决策。
     */
    public static SecurityDecision error(HttpResponseStatus status,
            String filterName, String reason) {
        return new SecurityDecision(SecurityDecisionType.ERROR,
                status, filterName, reason, null);
    }

    public SecurityDecisionType getType() {
        return type;
    }

    public HttpResponseStatus getStatus() {
        return status;
    }

    public String getFilterName() {
        return filterName;
    }

    public String getReason() {
        return reason;
    }

    public Integer getRetryAfterSeconds() {
        return retryAfterSeconds;
    }

    public boolean isAllowed() {
        return type == SecurityDecisionType.ALLOW;
    }
}
