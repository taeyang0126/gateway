package com.lei.gateway.proxy;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PathRewriterTest {

    // ---- 基本重写 ----

    @Test
    void rewritesPrefixWithCaptureGroup() {
        String result = PathRewriter.rewrite(
                "/api/perf/route1/hello",
                "^/api/perf/route1(.*)",
                "/api/example$1");
        assertThat(result).isEqualTo("/api/example/hello");
    }

    @Test
    void preservesQueryString() {
        String result = PathRewriter.rewrite(
                "/api/perf/route1/hello?foo=bar&baz=1",
                "^/api/perf/route1(.*)",
                "/api/example$1");
        assertThat(result).isEqualTo("/api/example/hello?foo=bar&baz=1");
    }

    @Test
    void rewritesWithEmptySegment() {
        // /api/perf/route1 → /api/example（segment 为空字符串）
        String result = PathRewriter.rewrite(
                "/api/perf/route1",
                "^/api/perf/route1(.*)",
                "/api/example$1");
        assertThat(result).isEqualTo("/api/example");
    }

    @Test
    void rewritesToFixedPath() {
        // 固定重写，不使用捕获组
        String result = PathRewriter.rewrite(
                "/api/perf/hello",
                "^/api/perf/hello(.*)",
                "/api/example/hello$1");
        assertThat(result).isEqualTo("/api/example/hello");
    }

    @Test
    void rewritesSlowRoute() {
        String result = PathRewriter.rewrite(
                "/api/perf/slow",
                "^/api/perf/slow(.*)",
                "/api/example/delay/slow$1");
        assertThat(result).isEqualTo("/api/example/delay/slow");
    }

    @Test
    void multipleCaptureGroups() {
        String result = PathRewriter.rewrite(
                "/v1/users/123",
                "^/v1/(\\w+)/(\\d+)",
                "/api/$1/id/$2");
        assertThat(result).isEqualTo("/api/users/id/123");
    }

    // ---- 无重写（pattern/replacement 为空）----

    @Test
    void returnsOriginalWhenPatternNull() {
        String result = PathRewriter.rewrite("/api/example/hello", null, "/new/path");
        assertThat(result).isEqualTo("/api/example/hello");
    }

    @Test
    void returnsOriginalWhenPatternBlank() {
        String result = PathRewriter.rewrite("/api/example/hello", "  ", "/new/path");
        assertThat(result).isEqualTo("/api/example/hello");
    }

    @Test
    void returnsOriginalWhenReplacementNull() {
        String result = PathRewriter.rewrite("/api/example/hello", "^/api(.*)", null);
        assertThat(result).isEqualTo("/api/example/hello");
    }

    @Test
    void returnsOriginalWhenReplacementBlank() {
        String result = PathRewriter.rewrite("/api/example/hello", "^/api(.*)", "");
        assertThat(result).isEqualTo("/api/example/hello");
    }

    @Test
    void returnsNullWhenUriNull() {
        String result = PathRewriter.rewrite(null, "^/api(.*)", "/new$1");
        assertThat(result).isNull();
    }

    // ---- 不匹配时原样返回 ----

    @Test
    void returnsOriginalWhenPatternDoesNotMatch() {
        String result = PathRewriter.rewrite(
                "/api/other/hello",
                "^/api/perf/route1(.*)",
                "/api/example$1");
        assertThat(result).isEqualTo("/api/other/hello");
    }

    // ---- 非法正则降级 ----

    @Test
    void returnsOriginalWhenPatternInvalid() {
        // 非法正则，应降级返回原始 URI 而不抛异常
        String result = PathRewriter.rewrite(
                "/api/example/hello",
                "[invalid(regex",
                "/new/path");
        assertThat(result).isEqualTo("/api/example/hello");
    }
}
