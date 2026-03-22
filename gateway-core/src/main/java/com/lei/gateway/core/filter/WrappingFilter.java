package com.lei.gateway.core.filter;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpRequest;
import java.util.concurrent.CompletableFuture;

/**
 * 标记接口：实现此接口的 Filter 可包装 ProxyHandler 的调用，控制请求发送循环。
 * FilterChainHandler 在 pre 链完成后检测链中是否存在 WrappingFilter，
 * 若存在则委托其 executeWithProxy()，不直接 fireChannelRead。
 * 这样 FilterChainHandler 无需感知具体的 RetryFilter 类型。
 */
public interface WrappingFilter extends Filter {

    /**
     * 包装 ProxyHandler 的调用，控制请求发送循环（如重试）。
     *
     * @param ctx          Netty ChannelHandlerContext
     * @param request      HTTP 请求
     * @param context      请求级 FilterContext
     * @param proxyInvoker ProxyHandler 单次调用封装
     * @return 完成 Future
     */
    CompletableFuture<Void> executeWithProxy(ChannelHandlerContext ctx,
            HttpRequest request, FilterContext context, ProxyInvoker proxyInvoker);
}
