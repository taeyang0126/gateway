package com.lei.gateway.core.proxy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 路径重写工具类，基于 Java 正则对请求 URI 做路径替换。
 *
 * <p>替换字符串支持数字捕获组引用（{@code $1}、{@code $2}）。
 * 不支持命名捕获组引用（{@code ${name}}），因为 YAML 中会被 Spring 当 placeholder 解析。
 *
 * <p>示例：
 * <pre>
 *   pattern:     ^/api/perf/route1(.*)
 *   replacement: /api/example$1
 *   输入：/api/perf/route1/hello?foo=bar
 *   输出：/api/example/hello?foo=bar
 * </pre>
 */
public final class PathRewriter {

    private static final Logger log = LoggerFactory.getLogger(PathRewriter.class);

    private PathRewriter() {
    }

    /**
     * 对 URI 执行路径重写。
     *
     * <p>pattern 或 replacement 为空时原样返回 uri。
     * 正则匹配失败或抛出异常时输出 warn 日志并原样返回 uri。
     *
     * @param uri         原始请求 URI（含 query string）
     * @param pattern     Java 正则表达式
     * @param replacement 替换字符串，支持 {@code $1}、{@code $2} 等捕获组引用
     * @return 重写后的 URI
     */
    public static String rewrite(String uri, String pattern, String replacement) {
        if (uri == null) {
            return null;
        }
        if (pattern == null || pattern.isBlank()
                || replacement == null || replacement.isBlank()) {
            return uri;
        }
        try {
            return uri.replaceFirst(pattern, replacement);
        } catch (Exception ex) {
            log.warn("路径重写失败，pattern={} uri={}，使用原始 URI", pattern, uri, ex);
            return uri;
        }
    }
}
