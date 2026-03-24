package com.lei.gateway.core.security;

/**
 * 安全过滤器接口。
 */
public interface SecurityFilter {

    /**
     * 过滤器名称（用于观测和日志）。
     */
    String name();

    /**
     * 执行过滤决策。
     */
    SecurityDecision apply(SecurityRequestContext context);
}
