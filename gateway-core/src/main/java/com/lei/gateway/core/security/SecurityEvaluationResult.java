package com.lei.gateway.core.security;

/**
 * 安全过滤执行结果。
 */
public class SecurityEvaluationResult {

    private final SecurityDecision decision;
    private final SecurityRequestContext context;

    /**
     * 创建执行结果。
     */
    public SecurityEvaluationResult(SecurityDecision decision,
            SecurityRequestContext context) {
        this.decision = decision;
        this.context = context;
    }

    public SecurityDecision getDecision() {
        return decision;
    }

    public SecurityRequestContext getContext() {
        return context;
    }
}
