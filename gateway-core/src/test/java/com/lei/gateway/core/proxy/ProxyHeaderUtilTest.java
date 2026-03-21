package com.lei.gateway.core.proxy;

import static org.assertj.core.api.Assertions.assertThat;

import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpHeaders;
import org.junit.jupiter.api.Test;

class ProxyHeaderUtilTest {

    @Test
    void addsAllProxyHeaders() {
        HttpHeaders headers = new DefaultHttpHeaders();
        headers.set("Host", "gateway.example.com");

        ProxyHeaderUtil.addProxyHeaders(headers, "10.0.0.1", "upstream.local");

        assertThat(headers.get("X-Forwarded-For")).isEqualTo("10.0.0.1");
        assertThat(headers.get("X-Forwarded-Host")).isEqualTo("gateway.example.com");
        assertThat(headers.get("X-Forwarded-Proto")).isEqualTo("http");
        assertThat(headers.get("X-Real-IP")).isEqualTo("10.0.0.1");
        assertThat(headers.get("Host")).isEqualTo("upstream.local");
    }

    @Test
    void appendsToExistingXForwardedFor() {
        HttpHeaders headers = new DefaultHttpHeaders();
        headers.set("Host", "gateway.example.com");
        headers.set("X-Forwarded-For", "192.168.1.1");

        ProxyHeaderUtil.addProxyHeaders(headers, "10.0.0.1", "upstream.local");

        assertThat(headers.get("X-Forwarded-For")).isEqualTo("192.168.1.1, 10.0.0.1");
    }

    @Test
    void appendsToMultipleExistingXForwardedFor() {
        HttpHeaders headers = new DefaultHttpHeaders();
        headers.set("Host", "gateway.example.com");
        headers.set("X-Forwarded-For", "192.168.1.1, 172.16.0.1");

        ProxyHeaderUtil.addProxyHeaders(headers, "10.0.0.1", "upstream.local");

        assertThat(headers.get("X-Forwarded-For")).isEqualTo("192.168.1.1, 172.16.0.1, 10.0.0.1");
    }

    @Test
    void hostHeaderReplacedWithUpstream() {
        HttpHeaders headers = new DefaultHttpHeaders();
        headers.set("Host", "original.host.com:8080");

        ProxyHeaderUtil.addProxyHeaders(headers, "10.0.0.1", "backend.svc:9090");

        assertThat(headers.get("Host")).isEqualTo("backend.svc:9090");
        assertThat(headers.get("X-Forwarded-Host")).isEqualTo("original.host.com:8080");
    }

    @Test
    void noHostHeaderSkipsXForwardedHost() {
        HttpHeaders headers = new DefaultHttpHeaders();

        ProxyHeaderUtil.addProxyHeaders(headers, "10.0.0.1", "upstream.local");

        assertThat(headers.contains("X-Forwarded-Host")).isFalse();
        assertThat(headers.get("Host")).isEqualTo("upstream.local");
    }

    @Test
    void preservesOtherHeaders() {
        HttpHeaders headers = new DefaultHttpHeaders();
        headers.set("Host", "gateway.example.com");
        headers.set("Content-Type", "application/json");
        headers.set("Authorization", "Bearer token123");

        ProxyHeaderUtil.addProxyHeaders(headers, "10.0.0.1", "upstream.local");

        assertThat(headers.get("Content-Type")).isEqualTo("application/json");
        assertThat(headers.get("Authorization")).isEqualTo("Bearer token123");
    }
}
