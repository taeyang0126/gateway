package com.lei.gateway.core.filter;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.AttributeKey;
import io.netty.util.CharsetUtil;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 过滤器链 Netty Handler。
 *
 * <p>负责在 channelRead(HttpRequest) 时依次执行 pre 过滤器，所有 pre 通过后检测
 * WrappingFilter 并委托，提供 onUpstreamResponse 回调触发 post 链，捕获异常返回
 * HTTP 500，以及 filterChainTimeoutMs 超时返回 HTTP 504。
 */
public class FilterChainHandler extends ChannelInboundHandlerAdapter {

    private static final Logger log = LoggerFactory.getLogger(FilterChainHandler.class);

    /**
     * Channel Attribute key，用于在 FilterChainHandler 和 ProxyHandler 之间共享 FilterContext。
     */
    public static final AttributeKey<FilterContext> FILTER_CONTEXT_KEY =
            AttributeKey.valueOf("filterContext");

    /**
     * 超时哨兵值：通过 CompletableFuture.complete(TIMEOUT_SENTINEL) 通知异步链短路。
     * 使用 ABORT 语义，pre 链检测到哨兵后不再继续执行。
     */
    private static final FilterResult TIMEOUT_SENTINEL = FilterResult.ABORT;

    private final List<Filter> prePostFilters;
    private final WrappingFilter wrappingFilter;
    private final long filterChainTimeoutMs;

    // 请求级状态（每次 channelRead 重置）
    private FilterContext filterContext;
    private ScheduledFuture<?> timeoutTask;
    private final AtomicBoolean timedOut = new AtomicBoolean(false);
    private final AtomicBoolean completed = new AtomicBoolean(false);

    /**
     * 创建 FilterChainHandler。
     *
     * @param prePostFilters      参与 pre/post 链的过滤器列表（已排序）
     * @param wrappingFilter      可选的 WrappingFilter（如 RetryFilter），null 表示无
     * @param filterChainTimeoutMs 过滤器链总超时（毫秒），0 表示不限制
     */
    public FilterChainHandler(List<Filter> prePostFilters,
            WrappingFilter wrappingFilter,
            long filterChainTimeoutMs) {
        this.prePostFilters = prePostFilters;
        this.wrappingFilter = wrappingFilter;
        this.filterChainTimeoutMs = filterChainTimeoutMs;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (!(msg instanceof HttpRequest request)) {
            ctx.fireChannelRead(msg);
            return;
        }

        // 重置请求级状态
        timedOut.set(false);
        completed.set(false);
        filterContext = new FilterContext();
        ctx.channel().attr(FILTER_CONTEXT_KEY).set(filterContext);

        // 启动超时计时器
        if (filterChainTimeoutMs > 0) {
            timeoutTask = ctx.executor().schedule(
                    () -> handleTimeout(ctx), filterChainTimeoutMs, TimeUnit.MILLISECONDS);
        }

        // 执行 pre 链
        runPreChain(ctx, request, filterContext, 0)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        cancelTimeout();
                        if (!timedOut.get()) {
                            log.error("过滤器链 pre 执行异常", ex);
                            sendErrorAndComplete(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR,
                                    "Filter chain error");
                        }
                        return;
                    }
                    if (timedOut.get()) {
                        return;
                    }
                    if (result == FilterResult.ABORT) {
                        cancelTimeout();
                        markCompleted();
                        return;
                    }
                    // pre 链全部 CONTINUE，进入转发阶段
                    dispatchToProxy(ctx, request, filterContext);
                });
    }

    /**
     * 由 ProxyHandler 在收到 Upstream 首个 HttpResponse 帧时调用。
     * 触发 post 链逆序执行；post 链执行完毕后 ProxyHandler 再将响应头写出给 Client。
     *
     * <p>两种场景：
     * <ul>
     *   <li>无 WrappingFilter：直接逆序执行 post 链</li>
     *   <li>有 WrappingFilter（如 RetryFilter）：complete responseFuture，
     *       post 链由 ProxyInvoker 的 thenApply 回调在 responseFuture 完成后执行</li>
     * </ul>
     *
     * @param ctx      Netty ChannelHandlerContext
     * @param response Upstream 返回的 HttpResponse（可被 post 过滤器修改 headers）
     */
    public void onUpstreamResponse(ChannelHandlerContext ctx, HttpResponse response) {
        if (filterContext == null || timedOut.get()) {
            return;
        }

        // 若有 WrappingFilter 在等待响应，complete 其 Future；
        // post 链由 dispatchToProxy 中 ProxyInvoker 的 thenApply 回调负责执行
        CompletableFuture<HttpResponse> responseFuture =
                filterContext.get("__proxyResponseFuture__");
        if (responseFuture != null && !responseFuture.isDone()) {
            responseFuture.complete(response);
            return;
        }

        // 无 WrappingFilter 场景：直接执行 post 链
        runPostChain(ctx, response);
    }

    /**
     * 逆序执行 post 链。
     */
    private void runPostChain(ChannelHandlerContext ctx, HttpResponse response) {
        for (int i = prePostFilters.size() - 1; i >= 0; i--) {
            try {
                prePostFilters.get(i).post(ctx, response, filterContext);
            } catch (Exception e) {
                log.error("过滤器 [{}] post 执行异常", prePostFilters.get(i).name(), e);
            }
        }
    }

    /**
     * 递归执行 pre 链，index 为当前过滤器下标。
     * 返回的 Future 在所有 pre 执行完毕（或 ABORT）后完成。
     */
    private CompletableFuture<FilterResult> runPreChain(ChannelHandlerContext ctx,
            HttpRequest request, FilterContext context, int index) {
        if (index >= prePostFilters.size()) {
            return CompletableFuture.completedFuture(FilterResult.CONTINUE);
        }
        if (timedOut.get()) {
            return CompletableFuture.completedFuture(TIMEOUT_SENTINEL);
        }

        Filter filter = prePostFilters.get(index);
        CompletableFuture<FilterResult> filterFuture;
        try {
            filterFuture = filter.pre(ctx, request, context);
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }

        return filterFuture.thenCompose(result -> {
            if (result == FilterResult.ABORT || timedOut.get()) {
                return CompletableFuture.completedFuture(result);
            }
            return runPreChain(ctx, request, context, index + 1);
        });
    }

    /**
     * pre 链全部通过后，决定是委托 WrappingFilter 还是直接 fireChannelRead。
     */
    private void dispatchToProxy(ChannelHandlerContext ctx,
            HttpRequest request, FilterContext context) {
        if (wrappingFilter != null) {
            ProxyInvoker invoker = req -> {
                CompletableFuture<HttpResponse> responseFuture = new CompletableFuture<>();
                context.set("__proxyResponseFuture__", responseFuture);
                ctx.fireChannelRead(req);
                // post 链在每次 ProxyInvoker 完成后执行（含重试中间结果）
                return responseFuture.thenApply(response -> {
                    if (!timedOut.get()) {
                        runPostChain(ctx, response);
                    }
                    return response;
                });
            };

            wrappingFilter.executeWithProxy(ctx, request, context, invoker)
                    .whenComplete((v, ex) -> {
                        cancelTimeout();
                        if (ex != null && !timedOut.get()) {
                            log.error("WrappingFilter 执行异常", ex);
                            sendErrorAndComplete(ctx,
                                    HttpResponseStatus.INTERNAL_SERVER_ERROR,
                                    "Filter chain error");
                        } else {
                            markCompleted();
                        }
                    });
        } else {
            // 无 WrappingFilter，直接交给 ProxyHandler
            ctx.fireChannelRead(request);
        }
    }

    private void handleTimeout(ChannelHandlerContext ctx) {
        if (!timedOut.compareAndSet(false, true)) {
            return;
        }
        log.warn("过滤器链超时（{}ms），返回 HTTP 504", filterChainTimeoutMs);

        if (filterContext != null) {
            ScheduledFuture<?> retryDelayFuture =
                    filterContext.get(FilterContext.RETRY_DELAY_FUTURE);
            if (retryDelayFuture != null) {
                retryDelayFuture.cancel(false);
            }
            CompletableFuture<HttpResponse> responseFuture =
                    filterContext.get("__proxyResponseFuture__");
            if (responseFuture != null) {
                responseFuture.completeExceptionally(
                        new java.util.concurrent.TimeoutException("Filter chain timeout"));
            }
        }

        sendError(ctx, HttpResponseStatus.GATEWAY_TIMEOUT, "Filter chain timeout");
        ctx.channel().close();
    }

    private void cancelTimeout() {
        if (timeoutTask != null) {
            timeoutTask.cancel(false);
            timeoutTask = null;
        }
    }

    private void markCompleted() {
        completed.set(true);
    }

    private void sendErrorAndComplete(ChannelHandlerContext ctx,
            HttpResponseStatus status, String message) {
        markCompleted();
        sendError(ctx, status, message);
    }

    private static void sendError(ChannelHandlerContext ctx,
            HttpResponseStatus status, String message) {
        String json = "{\"status\":" + status.code()
                + ",\"error\":\"" + status.reasonPhrase()
                + "\",\"message\":\"" + escapeJson(message) + "\"}";
        byte[] bytes = json.getBytes(CharsetUtil.UTF_8);
        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, status,
                Unpooled.wrappedBuffer(bytes));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
        response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, bytes.length);
        response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }

    private static String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        cancelTimeout();
        log.error("FilterChainHandler 未捕获异常", cause);
        if (ctx.channel().isActive()) {
            sendError(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR,
                    "Internal server error");
        }
    }
}
