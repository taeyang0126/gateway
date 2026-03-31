package com.lei.gateway.proxy;

import static org.assertj.core.api.Assertions.assertThat;

import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.Http2Headers;
import java.util.List;
import java.util.Map;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

// Feature: upstream-h2-connection, Property 4: H2 → H1 响应头转换保留语义
class H2ToH1ConversionPropertyTest {

    private static final List<Integer> STATUS_CODES = List.of(
            200, 201, 204, 301, 302, 304, 400, 401, 403, 404, 500, 502, 503);

    @Provide
    Arbitrary<Integer> statusCodes() {
        return Arbitraries.of(STATUS_CODES);
    }

    /** 生成普通响应头名（非伪头部）。 */
    @Provide
    Arbitrary<String> headerNames() {
        return Arbitraries.of(
                "content-type", "content-length", "server", "date",
                "x-request-id", "cache-control", "etag", "vary",
                "x-custom-header", "set-cookie", "access-control-allow-origin");
    }

    @Provide
    Arbitrary<String> headerValues() {
        return Arbitraries.of(
                "application/json", "text/html; charset=utf-8", "1234",
                "nginx/1.25", "Thu, 01 Jan 2026 00:00:00 GMT", "no-cache",
                "W/\"abc123\"", "Accept-Encoding", "custom-value", "*");
    }

    /**
     * 生成带随机头部的 H2 响应头（含 :status 伪头部和普通头部）。
     */
    @Provide
    Arbitrary<Http2Headers> h2ResponseHeaders() {
        Arbitrary<Integer> statusArb = statusCodes();
        Arbitrary<List<String>> namesArb = headerNames().list().ofMinSize(0).ofMaxSize(5);
        Arbitrary<List<String>> valuesArb = headerValues().list().ofMinSize(0).ofMaxSize(5);

        return Combinators.combine(statusArb, namesArb, valuesArb)
                .as((status, names, values) -> {
                    Http2Headers headers = new DefaultHttp2Headers();
                    headers.status(String.valueOf(status));

                    int count = Math.min(names.size(), values.size());
                    for (int ii = 0; ii < count; ii++) {
                        headers.add(names.get(ii), values.get(ii));
                    }
                    return headers;
                });
    }

    // Property 4.1: HTTP 状态码等于 :status 值
    @Property(tries = 100)
    void statusCodeMatchesOriginal(@ForAll("h2ResponseHeaders") Http2Headers h2Headers) {
        HttpResponse response = H2HeaderConverter.toH1Response(h2Headers);
        int expected = Integer.parseInt(h2Headers.status().toString());
        assertThat(response.status().code())
                .as("H1 状态码应等于 :status 值")
                .isEqualTo(expected);
    }

    // Property 4.2: 原始普通头部全部保留
    @Property(tries = 100)
    void regularHeadersArePreserved(@ForAll("h2ResponseHeaders") Http2Headers h2Headers) {
        HttpResponse response = H2HeaderConverter.toH1Response(h2Headers);

        for (Map.Entry<CharSequence, CharSequence> entry : h2Headers) {
            String name = entry.getKey().toString();
            if (name.startsWith(":")) {
                continue;
            }
            List<String> h1Values = response.headers().getAll(name);
            assertThat(h1Values)
                    .as("普通头部 '%s' 应保留在 H1 响应中", name)
                    .isNotEmpty();
            assertThat(h1Values)
                    .as("头部 '%s' 的值应包含 '%s'", name, entry.getValue())
                    .contains(entry.getValue().toString());
        }
    }

    // Property 4.3: H1 响应中不包含 H2 伪头部（以 ':' 开头）
    @Property(tries = 100)
    void pseudoHeadersAreRemoved(@ForAll("h2ResponseHeaders") Http2Headers h2Headers) {
        HttpResponse response = H2HeaderConverter.toH1Response(h2Headers);

        for (Map.Entry<String, String> entry : response.headers()) {
            assertThat(entry.getKey())
                    .as("H1 响应头中不应包含伪头部")
                    .doesNotStartWith(":");
        }
    }

    // Property 4.4: H1 响应头数量等于 H2 中非伪头部数量
    @Property(tries = 100)
    void headerCountMatchesNonPseudoCount(@ForAll("h2ResponseHeaders") Http2Headers h2Headers) {
        HttpResponse response = H2HeaderConverter.toH1Response(h2Headers);

        long nonPseudoCount = 0;
        for (Map.Entry<CharSequence, CharSequence> entry : h2Headers) {
            if (!entry.getKey().toString().startsWith(":")) {
                nonPseudoCount++;
            }
        }

        assertThat((long) response.headers().size())
                .as("H1 响应头数量应等于 H2 非伪头部数量")
                .isEqualTo(nonPseudoCount);
    }

    // Property 4.5: HTTP 版本为 HTTP/1.1
    @Property(tries = 100)
    void httpVersionIsHttp11(@ForAll("h2ResponseHeaders") Http2Headers h2Headers) {
        HttpResponse response = H2HeaderConverter.toH1Response(h2Headers);
        assertThat(response.protocolVersion().text())
                .as("H1 响应版本应为 HTTP/1.1")
                .isEqualTo("HTTP/1.1");
    }
}
