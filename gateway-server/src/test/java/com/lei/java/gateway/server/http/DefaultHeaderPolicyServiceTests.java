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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.lei.java.gateway.server.config.HostRewriteMode;
import com.lei.java.gateway.server.config.MatchType;
import com.lei.java.gateway.server.config.RouteConfig;
import com.lei.java.gateway.server.config.UpstreamConfig;

import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.util.AsciiString;

class DefaultHeaderPolicyServiceTests {

    private static final CharSequence KEEP_ALIVE_HEADER = AsciiString.cached("Keep-Alive");

    @Test
    void shouldRemoveHopByHopHeadersAndSetForwardedHeadersWhenRewriteHost() {
        final HeaderPolicyService service = new DefaultHeaderPolicyService();
        final RouteConfig route = route(HostRewriteMode.REWRITE);
        final HttpHeaders inbound = new DefaultHttpHeaders();
        inbound.set(HttpHeaderNames.CONNECTION, "close");
        inbound.set(KEEP_ALIVE_HEADER, "timeout=5");
        inbound.set(HttpHeaderNames.HOST, "client.example.com");
        inbound.set("X-Forwarded-For", "10.0.0.1");
        inbound.set("X-Trace-Id", "trace-123");

        final HttpHeaders outbound = new DefaultHttpHeaders();
        service.applyRequestHeaders(inbound, outbound, route, "10.0.0.2", "trace-123");

        assertFalse(outbound.contains(KEEP_ALIVE_HEADER));
        assertFalse(outbound.contains("Proxy-Authenticate"));
        assertEquals("10.0.0.1, 10.0.0.2", outbound.get("X-Forwarded-For"));
        assertEquals("http", outbound.get("X-Forwarded-Proto"));
        assertEquals("client.example.com", outbound.get("X-Forwarded-Host"));
        assertEquals("127.0.0.1:9001", outbound.get(HttpHeaderNames.HOST));
        assertEquals("trace-123", outbound.get("X-Trace-Id"));
        assertEquals("keep-alive", outbound.get(HttpHeaderNames.CONNECTION));
    }

    @Test
    void shouldPreserveHostWhenModeIsPreserve() {
        final HeaderPolicyService service = new DefaultHeaderPolicyService();
        final RouteConfig route = route(HostRewriteMode.PRESERVE);
        final HttpHeaders inbound = new DefaultHttpHeaders();
        inbound.set(HttpHeaderNames.HOST, "origin.example.com");

        final HttpHeaders outbound = new DefaultHttpHeaders();
        service.applyRequestHeaders(inbound, outbound, route, "127.0.0.1", "trace-1");

        assertEquals("origin.example.com", outbound.get(HttpHeaderNames.HOST));
        assertEquals("origin.example.com", outbound.get("X-Forwarded-Host"));
    }

    @Test
    void shouldUseUpstreamHostAsForwardedHostWhenInboundHostMissing() {
        final HeaderPolicyService service = new DefaultHeaderPolicyService();
        final RouteConfig route = route(HostRewriteMode.REWRITE);
        final HttpHeaders inbound = new DefaultHttpHeaders();

        final HttpHeaders outbound = new DefaultHttpHeaders();
        service.applyRequestHeaders(inbound, outbound, route, "127.0.0.1", "trace-1");

        assertEquals("127.0.0.1:9001", outbound.get("X-Forwarded-Host"));
    }

    @Test
    void shouldKeepTraceIdValueAfterApply() {
        final HeaderPolicyService service = new DefaultHeaderPolicyService();
        final RouteConfig route = route(HostRewriteMode.REWRITE);
        final HttpHeaders outbound = new DefaultHttpHeaders();

        service.applyRequestHeaders(
                new DefaultHttpHeaders(), outbound, route, "127.0.0.1", "trace-generated");

        final String traceId = outbound.get("X-Trace-Id");
        assertNotNull(traceId);
        assertTrue(traceId.startsWith("trace-"));
    }

    private static RouteConfig route(final HostRewriteMode hostRewriteMode) {
        return new RouteConfig(
                "r1",
                1,
                MatchType.PREFIX,
                "/api/",
                hostRewriteMode,
                new UpstreamConfig("http", "127.0.0.1", 9001, 1000, 1000, 1000));
    }
}
