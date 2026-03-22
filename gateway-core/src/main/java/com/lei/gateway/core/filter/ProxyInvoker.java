package com.lei.gateway.core.filter;

import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import java.util.concurrent.CompletableFuture;

/**
 * 封装对 ProxyHandler 的单次调用，由 FilterChainHandler 提供实现。
 */
@FunctionalInterface
public interface ProxyInvoker {

    /**
     * 发起一次对 ProxyHandler 的调用，返回 Upstream 响应的 Future。
     *
     * @param request HTTP 请求
     * @return 响应 Future
     */
    CompletableFuture<HttpResponse> invoke(HttpRequest request);
}
