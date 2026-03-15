/*
 * Copyright (c) 2026 lei.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.lei.java.gateway.server.http;

import java.util.Objects;

import com.lei.java.gateway.server.config.HostRewriteMode;
import com.lei.java.gateway.server.config.RouteConfig;

import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpHeaders;

/** 阶段 1 默认请求头处理策略。 */
public final class DefaultHeaderPolicyService implements HeaderPolicyService {

    /**
     * 应用阶段 1 请求头策略。
     *
     * @param inboundRequestHeaders 入站请求头
     * @param outboundRequestHeaders 出站请求头
     * @param route 命中的路由
     * @param clientIp 客户端 IP
     * @param traceId 关联链路的 TraceId
     */
    @Override
    public void applyRequestHeaders(
            final HttpHeaders inboundRequestHeaders,
            final HttpHeaders outboundRequestHeaders,
            final RouteConfig route,
            final String clientIp,
            final String traceId) {
        Objects.requireNonNull(inboundRequestHeaders, "inboundRequestHeaders must not be null");
        Objects.requireNonNull(outboundRequestHeaders, "outboundRequestHeaders must not be null");
        Objects.requireNonNull(route, "route must not be null");
        Objects.requireNonNull(clientIp, "clientIp must not be null");
        Objects.requireNonNull(traceId, "traceId must not be null");

        outboundRequestHeaders.set(inboundRequestHeaders);
        removeHopByHopHeaders(outboundRequestHeaders);

        final String originalHost = inboundRequestHeaders.get(HttpHeaderNames.HOST);
        appendForwardedFor(outboundRequestHeaders, clientIp);
        outboundRequestHeaders.set(
                HttpHeaderConstants.X_FORWARDED_PROTO, HttpHeaderConstants.FORWARDED_PROTO_HTTP);
        if (originalHost != null && !originalHost.isBlank()) {
            outboundRequestHeaders.set(HttpHeaderConstants.X_FORWARDED_HOST, originalHost);
        } else {
            outboundRequestHeaders.set(
                    HttpHeaderConstants.X_FORWARDED_HOST, buildUpstreamHost(route));
        }

        if (route.hostRewriteMode() == HostRewriteMode.REWRITE) {
            outboundRequestHeaders.set(HttpHeaderNames.HOST, buildUpstreamHost(route));
        } else if (originalHost != null && !originalHost.isBlank()) {
            outboundRequestHeaders.set(HttpHeaderNames.HOST, originalHost);
        }
        outboundRequestHeaders.set(HttpHeaderConstants.TRACE_ID_HEADER, traceId);
        outboundRequestHeaders.set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
    }

    private static void removeHopByHopHeaders(final HttpHeaders headers) {
        for (CharSequence header : HttpHeaderConstants.HOP_BY_HOP_HEADERS) {
            headers.remove(header);
        }
    }

    private static void appendForwardedFor(final HttpHeaders headers, final String clientIp) {
        final String current = headers.get(HttpHeaderConstants.X_FORWARDED_FOR);
        if (current == null || current.isBlank()) {
            headers.set(HttpHeaderConstants.X_FORWARDED_FOR, clientIp);
            return;
        }
        headers.set(HttpHeaderConstants.X_FORWARDED_FOR, current + ", " + clientIp);
    }

    private static String buildUpstreamHost(final RouteConfig route) {
        final String upstreamHost = route.upstream().host();
        if (upstreamHost.contains(":")
                && !upstreamHost.startsWith("[")
                && !upstreamHost.endsWith("]")) {
            return "[" + upstreamHost + "]:" + route.upstream().port();
        }
        return upstreamHost + ":" + route.upstream().port();
    }
}
