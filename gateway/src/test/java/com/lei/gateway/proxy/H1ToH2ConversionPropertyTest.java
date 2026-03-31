package com.lei.gateway.proxy;

import static org.assertj.core.api.Assertions.assertThat;

import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http2.Http2Headers;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

// Feature: upstream-h2-connection, Property 3: H1 → H2 请求头转换保留语义
class H1ToH2ConversionPropertyTest {

    private static final Set<String> HOP_BY_HOP = Set.of(
            "connection", "transfer-encoding", "keep-alive", "proxy-connection", "upgrade");

    private static final List<HttpMethod> METHODS = List.of(
            HttpMethod.GET, HttpMethod.POST, HttpMethod.PUT,
            HttpMethod.DELETE, HttpMethod.PATCH, HttpMethod.HEAD, HttpMethod.OPTIONS);

    @Provide
    Arbitrary<HttpMethod> methods() {
        return Arbitraries.of(METHODS);
    }

    @Provide
    Arbitrary<String> paths() {
        return Arbitraries.of(
                "/", "/api/v1/users", "/search?q=hello&page=1",
                "/a/b/c/d/e", "/items/123", "/health",
                "/path/with%20space", "/中文路径");
    }

    @Provide
    Arbitrary<String> hosts() {
        return Arbitraries.of(
                "localhost:8080", "example.com", "10.0.0.1:9090",
                "api.internal.svc:443", "upstream-host");
    }

    /** 生成普通头部名（非 hop-by-hop、非 host）。 */
    @Provide
    Arbitrary<String> headerNames() {
        return Arbitraries.of(
                "accept", "content-type", "x-request-id", "authorization",
                "user-agent", "cache-control", "x-custom-header", "accept-encoding");
    }

    @Provide
    Arbitrary<String> headerValues() {
        return Arbitraries.of(
                "application/json", "text/html", "gzip, deflate",
                "Bearer token123", "Mozilla/5.0", "no-cache", "custom-value", "*/*");
    }

    /**
     * 生成带随机头部的 H1 请求（含 hop-by-hop 头和普通头）。
     */
    @Provide
    Arbitrary<HttpRequest> httpRequests() {
        Arbitrary<HttpMethod> methodArb = methods();
        Arbitrary<String> pathArb = paths();
        Arbitrary<String> hostArb = hosts();
        Arbitrary<List<String>> normalNameArb = headerNames().list().ofMinSize(0).ofMaxSize(5);
        Arbitrary<List<String>> normalValueArb = headerValues().list().ofMinSize(0).ofMaxSize(5);
        Arbitrary<Boolean> includeHopByHop = Arbitraries.of(true, false);

        return Combinators.combine(methodArb, pathArb, hostArb, normalNameArb, normalValueArb, includeHopByHop)
                .as((method, path, host, names, values, addHop) -> {
                    DefaultHttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, method, path);
                    request.headers().set("host", host);

                    int count = Math.min(names.size(), values.size());
                    for (int i = 0; i < count; i++) {
                        request.headers().add(names.get(i), values.get(i));
                    }

                    if (addHop) {
                        request.headers().add("connection", "keep-alive");
                        request.headers().add("transfer-encoding", "chunked");
                        request.headers().add("keep-alive", "timeout=5");
                        request.headers().add("proxy-connection", "keep-alive");
                        request.headers().add("upgrade", "h2c");
                    }
                    return request;
                });
    }

    // Property 3.1: :method 伪头部等于原始 method
    @Property(tries = 100)
    void methodPseudoHeaderMatchesOriginal(@ForAll("httpRequests") HttpRequest request) {
        Http2Headers h2 = H2HeaderConverter.toH2Headers(request);
        assertThat(h2.method().toString())
                .as(":method 应等于原始 method")
                .isEqualTo(request.method().asciiName().toString());
    }

    // Property 3.2: :path 伪头部等于原始 URI
    @Property(tries = 100)
    void pathPseudoHeaderMatchesOriginal(@ForAll("httpRequests") HttpRequest request) {
        Http2Headers h2 = H2HeaderConverter.toH2Headers(request);
        assertThat(h2.path().toString())
                .as(":path 应等于原始 URI")
                .isEqualTo(request.uri());
    }

    // Property 3.3: :scheme 伪头部为 http
    @Property(tries = 100)
    void schemePseudoHeaderIsHttp(@ForAll("httpRequests") HttpRequest request) {
        Http2Headers h2 = H2HeaderConverter.toH2Headers(request);
        assertThat(h2.scheme().toString())
                .as(":scheme 应为 http")
                .isEqualTo("http");
    }

    // Property 3.4: :authority 伪头部等于原始 Host 头
    @Property(tries = 100)
    void authorityPseudoHeaderMatchesHost(@ForAll("httpRequests") HttpRequest request) {
        Http2Headers h2 = H2HeaderConverter.toH2Headers(request);
        String host = request.headers().get("host");
        assertThat(h2.authority().toString())
                .as(":authority 应等于原始 Host 头")
                .isEqualTo(host);
    }

    // Property 3.5: hop-by-hop 头被移除
    @Property(tries = 100)
    void hopByHopHeadersAreRemoved(@ForAll("httpRequests") HttpRequest request) {
        Http2Headers h2 = H2HeaderConverter.toH2Headers(request);
        for (var entry : h2) {
            String name = entry.getKey().toString().toLowerCase(java.util.Locale.ROOT);
            assertThat(HOP_BY_HOP).as("H2 头中不应包含 hop-by-hop 头: %s", name)
                    .doesNotContain(name);
        }
    }

    // Property 3.6: 普通头部（排除 hop-by-hop 和 host）全部保留
    @Property(tries = 100)
    void regularHeadersArePreserved(@ForAll("httpRequests") HttpRequest request) {
        Http2Headers h2 = H2HeaderConverter.toH2Headers(request);

        HttpHeaders h1Headers = request.headers();
        Set<String> h2Names = new HashSet<>();
        for (var entry : h2) {
            String name = entry.getKey().toString();
            if (!name.startsWith(":")) {
                h2Names.add(name);
            }
        }

        for (var entry : h1Headers) {
            String name = entry.getKey().toLowerCase(java.util.Locale.ROOT);
            if (name.equals("host") || HOP_BY_HOP.contains(name)) {
                continue;
            }
            assertThat(h2Names).as("普通头部 '%s' 应保留在 H2 头中", name)
                    .contains(name);

            // 验证值也一致
            List<CharSequence> h2Values = h2.getAll(name);
            assertThat(h2Values).as("头部 '%s' 的值应包含 '%s'", name, entry.getValue())
                    .anySatisfy(val -> assertThat(val.toString()).isEqualTo(entry.getValue()));
        }
    }

    // Property 3.7: H2 头中不包含 host 头（已映射到 :authority）
    @Property(tries = 100)
    void hostHeaderNotInH2Output(@ForAll("httpRequests") HttpRequest request) {
        Http2Headers h2 = H2HeaderConverter.toH2Headers(request);
        for (var entry : h2) {
            String name = entry.getKey().toString();
            if (!name.startsWith(":")) {
                assertThat(name).as("H2 头中不应包含 host 头（已映射到 :authority）")
                        .isNotEqualTo("host");
            }
        }
    }
}
