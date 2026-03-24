package com.lei.gateway.core.proxy;

import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;

/**
 * 代理请求头处理工具类。
 *
 * <p>为转发请求添加标准代理请求头（参考 RFC 7239 和 Nginx 标准实践）。
 */
public final class ProxyHeaderUtil {

    private static final String X_FORWARDED_FOR = "X-Forwarded-For";
    private static final String X_FORWARDED_HOST = "X-Forwarded-Host";
    private static final String X_FORWARDED_PROTO = "X-Forwarded-Proto";
    private static final String X_REAL_IP = "X-Real-IP";

    private ProxyHeaderUtil() {
    }

    /**
     * 为转发请求添加标准代理请求头。
     *
     * @param headers      原始请求头（将被修改）
     * @param realClientIp 客户端真实 IP
     * @param remoteClientIp 与网关建立连接的对端 IP（上一跳）
     * @param upstreamHost upstream 的 host
     */
    public static void addProxyHeaders(HttpHeaders headers, String realClientIp,
            String remoteClientIp, String upstreamHost) {
        String xffHopIp = isBlank(remoteClientIp) ? realClientIp : remoteClientIp;
        // X-Forwarded-For：已有则逗号分隔追加，否则新增
        String existing = headers.get(X_FORWARDED_FOR);
        if (existing != null && !existing.isEmpty()) {
            headers.set(X_FORWARDED_FOR, existing + ", " + xffHopIp);
        } else {
            headers.set(X_FORWARDED_FOR, xffHopIp);
        }

        // X-Forwarded-Host：原始 Host 头值
        String originalHost = headers.get(HttpHeaderNames.HOST);
        if (originalHost != null) {
            headers.set(X_FORWARDED_HOST, originalHost);
        }

        // X-Forwarded-Proto：默认 http（Netty 原生不处理 TLS 时）
        headers.set(X_FORWARDED_PROTO, "http");

        // X-Real-IP
        headers.set(X_REAL_IP, realClientIp);

        // Host 修改为 upstream 的 host
        headers.set(HttpHeaderNames.HOST, upstreamHost);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
