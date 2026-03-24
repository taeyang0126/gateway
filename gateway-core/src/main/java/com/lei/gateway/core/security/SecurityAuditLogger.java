package com.lei.gateway.core.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 安全过滤审计日志输出。
 */
public class SecurityAuditLogger {

    private static final Logger log = LoggerFactory.getLogger("security-audit");

    /**
     * 输出安全审计日志。
     */
    public void log(SecurityRequestContext context, SecurityDecision decision) {
        log.info("traceId={} clientIp={} routeId={} filterName={} decision={} reason={}",
                context.getTraceId(),
                context.getClientIp(),
                context.getRouteId(),
                decision.getFilterName(),
                decision.getType(),
                decision.getReason());
    }
}
