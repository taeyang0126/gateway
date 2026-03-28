package com.lei.gateway.core.proxy;

import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.Http2Headers;
import java.util.Set;

/**
 * H1 ↔ H2 协议头转换工具类。
 *
 * <p>H1→H2：将 {@link HttpRequest} 的请求头转换为 {@link Http2Headers}，
 * 生成伪头部（:method, :path, :scheme, :authority），去除 hop-by-hop 头。
 *
 * <p>H2→H1：将 {@link Http2Headers} 响应头转换为 {@link HttpResponse}，
 * :status → 状态码，去除伪头部。
 */
public final class H2HeaderConverter {

    /** H1 hop-by-hop 头，H2 中不允许出现。 */
    private static final Set<CharSequence> HOP_BY_HOP_HEADERS = Set.of(
            "connection",
            "transfer-encoding",
            "keep-alive",
            "proxy-connection",
            "upgrade"
    );

    private H2HeaderConverter() {
    }

    /**
     * 将 H1 请求头转换为 H2 HEADERS 帧的头部。
     *
     * <p>生成伪头部：:method, :path, :scheme(http), :authority。
     * 去除 hop-by-hop 头（Connection, Transfer-Encoding, Keep-Alive, Proxy-Connection, Upgrade）。
     * 普通头部直接映射。
     *
     * @param request H1 请求
     * @return H2 请求头
     */
    public static Http2Headers toH2Headers(HttpRequest request) {
        Http2Headers h2Headers = new DefaultHttp2Headers();
        h2Headers.method(request.method().asciiName());
        h2Headers.path(request.uri());
        h2Headers.scheme("http");

        String host = request.headers().get("host");
        if (host != null) {
            h2Headers.authority(host);
        }

        HttpHeaders h1Headers = request.headers();
        for (var entry : h1Headers) {
            String name = entry.getKey().toLowerCase(java.util.Locale.ROOT);
            if (name.equals("host")) {
                // host 已映射到 :authority
                continue;
            }
            if (HOP_BY_HOP_HEADERS.contains(name)) {
                continue;
            }
            h2Headers.add(name, entry.getValue());
        }
        return h2Headers;
    }

    /**
     * 将 H2 响应头转换为 H1 {@link DefaultHttpResponse}。
     *
     * <p>:status 伪头部 → HTTP 状态码，去除所有伪头部（以 ':' 开头）。
     *
     * @param h2Headers H2 响应头
     * @return H1 响应对象
     */
    public static HttpResponse toH1Response(Http2Headers h2Headers) {
        int statusCode = h2Headers.status() != null
                ? Integer.parseInt(h2Headers.status().toString())
                : 200;
        DefaultHttpResponse response = new DefaultHttpResponse(
                HttpVersion.HTTP_1_1, HttpResponseStatus.valueOf(statusCode));
        h2Headers.forEach(entry -> {
            String name = entry.getKey().toString();
            if (!name.startsWith(":")) {
                response.headers().add(name, entry.getValue().toString());
            }
        });
        return response;
    }
}
