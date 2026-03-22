package com.lei.gateway.core.filter;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import java.util.concurrent.CompletableFuture;

/**
 * 过滤器接口，定义请求前置处理和响应后置处理两个扩展点。
 *
 * <p>pre() 返回 CompletableFuture&lt;FilterResult&gt;，支持异步操作（如鉴权 HTTP 调用）。
 * post() 为同步操作，在 Netty EventLoop 线程上执行。
 */
public interface Filter {

    /**
     * 过滤器名称，用于日志和配置标识。
     */
    String name();

    /**
     * 过滤器执行优先级，值越小越先执行。
     * 路由显式配置顺序时忽略此值；未配置时按此值升序排列。
     * 内置过滤器默认 order 常量：
     *   IpAccessControl=100, PreAuthRateLimit=200, Auth=300,
     *   PostAuthRateLimit=400, HeaderTransform=500, CircuitBreaker=600
     * 注：RetryFilter 实现 WrappingFilter 接口，不参与 pre/post 链排序，
     *     不定义 order 常量，FilterChainFactory 构建链时将其单独提取。
     */
    default int getOrder() {
        return 0;
    }

    /**
     * 前置处理：在请求转发至 Upstream 前执行。
     *
     * @param ctx     Netty ChannelHandlerContext
     * @param request 原始 HTTP 请求（可修改 headers）
     * @param context 请求级 FilterContext
     * @return CompletableFuture&lt;FilterResult&gt;，CONTINUE 表示继续，ABORT 表示终止并已写出响应
     */
    CompletableFuture<FilterResult> pre(ChannelHandlerContext ctx,
            HttpRequest request, FilterContext context);

    /**
     * 后置处理：在 Upstream 响应返回后执行（逆序）。
     * 默认空实现，无需后置处理的过滤器无需覆盖。
     *
     * @param ctx      Netty ChannelHandlerContext
     * @param response Upstream 返回的 HTTP 响应（可修改 headers）
     * @param context  请求级 FilterContext
     */
    default void post(ChannelHandlerContext ctx, HttpResponse response,
            FilterContext context) {
        // 默认空实现
    }
}
