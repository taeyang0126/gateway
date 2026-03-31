package com.lei.gateway.example.upstream;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/**
 * 从 traceparent 请求头提取 traceId 写入 MDC，请求结束后清理。
 * traceparent 格式：{version}-{traceId}-{spanId}-{flags}
 */
@Component
public class TraceIdFilter implements Filter {

    private static final String TRACEPARENT_HEADER = "traceparent";
    private static final String MDC_TRACE_ID = "traceId";

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        String traceId = null;
        if (request instanceof HttpServletRequest httpRequest) {
            String traceparent = httpRequest.getHeader(TRACEPARENT_HEADER);
            if (traceparent != null) {
                // 格式：00-{traceId(32hex)}-{spanId(16hex)}-{flags}
                String[] parts = traceparent.split("-", 4);
                if (parts.length == 4) {
                    traceId = parts[1];
                }
            }
        }
        if (traceId != null) {
            MDC.put(MDC_TRACE_ID, traceId);
        }
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_TRACE_ID);
        }
    }
}
