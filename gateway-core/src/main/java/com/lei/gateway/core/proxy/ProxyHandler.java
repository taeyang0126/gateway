package com.lei.gateway.core.proxy;

import com.lei.gateway.core.config.ObservabilityProperties;
import com.lei.gateway.core.config.RequestLimitProperties;
import com.lei.gateway.core.config.Route;
import com.lei.gateway.core.observability.AccessLogEntry;
import com.lei.gateway.core.observability.AccessLogWriter;
import com.lei.gateway.core.observability.MetricsCollector;
import com.lei.gateway.core.observability.TraceContextHandler;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.util.CharsetUtil;
import io.netty.util.ReferenceCountUtil;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 统一的全流式转发处理器，处理所有类型的请求。
 *
 * <p>处理流程：HttpRequest（请求头）→ N 个 HttpContent（请求体块）→
 * LastHttpContent（请求体结束）→ 等待 Upstream 响应 → 流式回传。
 */
public class ProxyHandler extends ChannelInboundHandlerAdapter {

    private static final Logger log = LoggerFactory.getLogger(ProxyHandler.class);

    private final Route route;
    private final RequestLimitProperties limitConfig;
    private final UpstreamConnectionPool connectionPool;
    private final MetricsCollector metricsCollector;
    private final AccessLogWriter accessLogWriter;
    private final ObservabilityProperties observabilityConfig;

    // 请求级状态
    private long startTimeNanos;
    private String traceId;
    private String method;
    private String path;
    private String clientIp;
    private long requestBodySize;
    private long responseBodySize;
    private int responseStatusCode;
    private long maxRequestSize;
    private Channel upstreamChannel;
    private boolean connectingToUpstream;
    private Queue<HttpContent> pendingContent;
    private final AtomicBoolean requestCompleted = new AtomicBoolean(false);

    /** 创建 ProxyHandler。 */
    public ProxyHandler(Route route,
            RequestLimitProperties limitConfig,
            UpstreamConnectionPool connectionPool,
            MetricsCollector metricsCollector,
            AccessLogWriter accessLogWriter,
            ObservabilityProperties observabilityConfig) {
        this.route = route;
        this.limitConfig = limitConfig;
        this.connectionPool = connectionPool;
        this.metricsCollector = metricsCollector;
        this.accessLogWriter = accessLogWriter;
        this.observabilityConfig = observabilityConfig;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg)
            throws Exception {
        if (msg instanceof HttpRequest request) {
            handleHttpRequest(ctx, request);
        } else if (msg instanceof HttpContent content) {
            handleHttpContent(ctx, content);
        } else {
            ctx.fireChannelRead(msg);
        }
    }

    private void handleHttpRequest(ChannelHandlerContext ctx,
            HttpRequest request) {
        // 1. 记录请求开始时间
        startTimeNanos = System.nanoTime();

        // 2. 读取 trace-id
        traceId = ctx.channel().attr(TraceContextHandler.TRACE_ID_KEY).get();

        // 3. 记录请求基本信息
        method = request.method().name();
        path = request.uri();
        java.net.SocketAddress remoteAddr = ctx.channel().remoteAddress();
        if (remoteAddr instanceof InetSocketAddress inetAddr) {
            clientIp = inetAddr.getAddress().getHostAddress();
        } else {
            clientIp = remoteAddr != null ? remoteAddr.toString() : "unknown";
        }

        // 4. 记录客户端是否发送了 Expect: 100-continue
        boolean expectContinue = HttpUtil.is100ContinueExpected(request);

        // 5. Content-Length 预检（路由级别 maxRequestSize 优先于全局）
        maxRequestSize = route.getMaxRequestSize() != null
                ? route.getMaxRequestSize()
                : limitConfig.getMaxRequestSize();
        long contentLength = HttpUtil.getContentLength(request, -1L);
        if (contentLength > maxRequestSize) {
            sendErrorAndCleanup(ctx, HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE,
                    "Request body too large: " + contentLength
                    + " bytes exceeds limit of " + maxRequestSize + " bytes");
            return;
        }

        // 如果客户端带了 Expect: 100-continue，不向客户端主动回 100。
        // 实测该交互在当前 pipeline 下可能导致客户端收不到最终 200 而卡住。
        // 这里仅去掉转发给上游的 Expect 头，避免上游再回 100 干扰响应序列。
        if (expectContinue) {
            request.headers().remove(HttpHeaderNames.EXPECT);
        }

        // 6. 解析 upstream 地址
        URI upstreamUri;
        try {
            upstreamUri = URI.create(route.getUpstream());
        } catch (IllegalArgumentException e) {
            log.error("无效的 upstream 地址: {}", route.getUpstream(), e);
            sendErrorAndCleanup(ctx, HttpResponseStatus.BAD_GATEWAY,
                    "Invalid upstream address");
            return;
        }
        String upstreamHost = upstreamUri.getHost();
        int upstreamPort = upstreamUri.getPort() > 0
                ? upstreamUri.getPort() : 80;

        // 6. 标记正在连接 upstream，后续 HttpContent 会被缓冲
        connectingToUpstream = true;

        // 7. 准备请求头（在异步回调前完成，避免 request 对象被回收）
        HttpHeaders headers = request.headers();
        ProxyHeaderUtil.addProxyHeaders(headers, clientIp,
                upstreamHost + (upstreamPort != 80
                        ? ":" + upstreamPort : ""));
        if (observabilityConfig.isTracingEnabled()) {
            String traceparent = ctx.channel()
                    .attr(TraceContextHandler.TRACEPARENT_KEY).get();
            if (traceparent != null) {
                headers.set("traceparent", traceparent);
            }
            String tracestate = ctx.channel()
                    .attr(TraceContextHandler.TRACESTATE_KEY).get();
            if (tracestate != null) {
                headers.set("tracestate", tracestate);
            }
        }
        headers.set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);

        DefaultHttpRequest upstreamRequest = new DefaultHttpRequest(
                HttpVersion.HTTP_1_1, request.method(), request.uri(),
                request.headers());

        // 8. 异步获取 upstream 连接
        connectionPool.acquire(upstreamHost, upstreamPort)
                .whenComplete((channel, ex) -> {
                    // 确保回调在客户端 EventLoop 上执行
                    if (ctx.channel().eventLoop().inEventLoop()) {
                        onAcquireComplete(ctx, upstreamRequest, channel, ex);
                    } else {
                        ctx.channel().eventLoop().execute(() ->
                                onAcquireComplete(ctx, upstreamRequest,
                                        channel, ex));
                    }
                });
    }

    private void onAcquireComplete(ChannelHandlerContext ctx,
            DefaultHttpRequest upstreamRequest,
            Channel channel, Throwable ex) {
        if (!ctx.channel().isActive()) {
            // 客户端已断开
            if (channel != null) {
                channel.close();
            }
            return;
        }

        if (ex != null) {
            Throwable cause = ex instanceof java.util.concurrent.CompletionException
                    ? ex.getCause() : ex;
            if (cause instanceof IllegalStateException) {
                log.error("连接池已满，无法获取 upstream 连接: {}",
                        route.getUpstream(), cause);
                sendErrorAndCleanup(ctx, HttpResponseStatus.SERVICE_UNAVAILABLE,
                        "Connection pool exhausted for upstream: "
                        + route.getUpstream());
            } else {
                log.error("获取 upstream 连接失败: {}",
                        route.getUpstream(), cause);
                sendErrorAndCleanup(ctx, HttpResponseStatus.BAD_GATEWAY,
                        "Failed to connect to upstream: "
                        + route.getUpstream());
            }
            return;
        }

        upstreamChannel = channel;
        connectingToUpstream = false;

        // 设置 upstream 响应处理器
        upstreamChannel.pipeline().addLast("upstreamHandler",
                new UpstreamResponseHandler(ctx, this));

        // 转发请求头到 upstream（不立即 flush，等待后续 content 一起 flush）
        upstreamChannel.write(upstreamRequest);

        // flush 连接就绪前缓冲的 HttpContent（逐块 flush，避免大包长时间滞留在写缓冲）
        if (pendingContent != null) {
            HttpContent buffered;
            while ((buffered = pendingContent.poll()) != null) {
                upstreamChannel.write(buffered);
            }
            pendingContent = null;
        }
        upstreamChannel.flush();
    }

    private void handleHttpContent(ChannelHandlerContext ctx,
            HttpContent content) {
        // 累计字节数检查（无论连接是否就绪都要检查）
        int readableBytes = content.content().readableBytes();
        requestBodySize += readableBytes;
        if (requestBodySize > maxRequestSize) {
            ReferenceCountUtil.release(content);
            sendErrorAndCleanup(ctx, HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE,
                    "Request body too large: accumulated "
                    + requestBodySize + " bytes exceeds limit of "
                    + maxRequestSize + " bytes");
            return;
        }

        // upstream 连接尚未就绪，缓冲 content
        if (connectingToUpstream) {
            if (pendingContent == null) {
                pendingContent = new ArrayDeque<>();
            }
            // 当前 handler 已持有 content 的所有权，直接入队即可，避免额外 retain 导致泄漏
            pendingContent.add(content);
            return;
        }

        if (upstreamChannel == null || !upstreamChannel.isActive()) {
            ReferenceCountUtil.release(content);
            return;
        }

        // 请求体逐块 flush，保证上游及时消费，避免大文件上传卡住
        upstreamChannel.writeAndFlush(content);
    }

    /**
     * 处理 upstream 响应头，转发给 client（分块响应专用，不含 body）。
     */
    void handleUpstreamResponse(ChannelHandlerContext clientCtx,
            HttpResponse response) {
        responseStatusCode = response.status().code();
        long contentLength = HttpUtil.getContentLength(response, -1L);
        boolean chunked = HttpUtil.isTransferEncodingChunked(response);
        if (isNoBodyResponse(response)) {
            FullHttpResponse fullResponse = new DefaultFullHttpResponse(
                    response.protocolVersion(), response.status(),
                    io.netty.buffer.Unpooled.EMPTY_BUFFER, response.headers(),
                    io.netty.handler.codec.http.EmptyHttpHeaders.INSTANCE);
            clientCtx.writeAndFlush(fullResponse).addListener(future -> {
                if (!future.isSuccess()) {
                    log.error("写出响应头到客户端失败", future.cause());
                }
                completeRequest(clientCtx);
            });
            return;
        }
        // 上游要求关闭连接，或响应无明确长度边界时，响应后关闭客户端连接，避免客户端等待结束信号卡住
        if (!HttpUtil.isKeepAlive(response)
                || (!chunked && contentLength < 0)) {
            response.headers().set(HttpHeaderNames.CONNECTION,
                    HttpHeaderValues.CLOSE);
            clientCtx.channel().attr(RoutingHandler.CONNECTION_CLOSE_KEY)
                    .set(Boolean.TRUE);
        }
        // 响应头先刷新，避免客户端在等待首包时长时间挂起
        clientCtx.writeAndFlush(response);
    }

    /**
     * 처리 upstream 响应体块，逐块转发给 client。
     */
    void handleUpstreamContent(ChannelHandlerContext clientCtx,
            HttpContent content) {
        responseBodySize += content.content().readableBytes();

        if (content instanceof LastHttpContent) {
            clientCtx.writeAndFlush(content).addListener(future -> {
                if (!future.isSuccess()) {
                    log.error("写出响应到客户端失败", future.cause());
                }
                completeRequest(clientCtx);
            });
        } else {
            clientCtx.writeAndFlush(content);
        }
    }

    private void completeRequest(ChannelHandlerContext clientCtx) {
        if (!requestCompleted.compareAndSet(false, true)) {
            return;
        }
        // 计算耗时
        long durationNanos = System.nanoTime() - startTimeNanos;

        // 记录指标
        metricsCollector.recordRequest(method, path, responseStatusCode,
                durationNanos);

        // 输出访问日志
        AccessLogEntry logEntry = new AccessLogEntry();
        logEntry.setMethod(method);
        logEntry.setPath(path);
        logEntry.setStatusCode(responseStatusCode);
        logEntry.setDurationMs(durationNanos / 1_000_000);
        logEntry.setClientIp(clientIp);
        logEntry.setUpstream(route.getUpstream());
        logEntry.setRequestBodySize(requestBodySize);
        logEntry.setResponseBodySize(responseBodySize);
        logEntry.setTraceId(traceId);
        accessLogWriter.log(logEntry);

        // 归还连接
        releaseUpstream(true);

        // 从 Pipeline 移除自身，通知 RoutingHandler
        RoutingHandler.onProxyComplete(clientCtx);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        // Client 断开连接，释放资源，关闭 upstream（不归还连接池）
        releaseUpstream(false);
        super.channelInactive(ctx);
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt)
            throws Exception {
        if (evt instanceof IdleStateEvent) {
            sendErrorAndCleanup(ctx, HttpResponseStatus.GATEWAY_TIMEOUT,
                    "Request timeout");
            return;
        }
        super.userEventTriggered(ctx, evt);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("ProxyHandler 异常", cause);
        releaseUpstream(false);
        if (ctx.channel().isActive()) {
            sendError(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR,
                    "Internal server error");
        }
    }

    private void sendErrorAndCleanup(ChannelHandlerContext ctx,
            HttpResponseStatus status, String message) {
        requestCompleted.set(true);
        releaseUpstream(false);
        sendError(ctx, status, message);
        RoutingHandler.onProxyComplete(ctx);
    }

    private void sendError(ChannelHandlerContext ctx,
            HttpResponseStatus status, String message) {
        String json = "{\"status\":" + status.code()
                + ",\"error\":\"" + status.reasonPhrase()
                + "\",\"message\":\"" + escapeJson(message) + "\"}";
        ByteBuf content = Unpooled.copiedBuffer(json, CharsetUtil.UTF_8);
        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, status, content);
        response.headers().set(HttpHeaderNames.CONTENT_TYPE,
                "application/json");
        response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH,
                content.readableBytes());
        response.headers().set(HttpHeaderNames.CONNECTION,
                HttpHeaderValues.CLOSE);
        ctx.writeAndFlush(response)
                .addListener(ChannelFutureListener.CLOSE);
    }

    private void releaseUpstream(boolean requite) {
        connectingToUpstream = false;
        // 释放缓冲的 HttpContent
        if (pendingContent != null) {
            HttpContent buffered;
            while ((buffered = pendingContent.poll()) != null) {
                ReferenceCountUtil.release(buffered);
            }
            pendingContent = null;
        }
        if (upstreamChannel != null) {
            // 移除 upstream handler 避免重复处理
            if (upstreamChannel.pipeline().get("upstreamHandler") != null) {
                upstreamChannel.pipeline().remove("upstreamHandler");
            }
            if (requite) {
                connectionPool.release(upstreamChannel);
            } else {
                connectionPool.remove(upstreamChannel);
            }
            upstreamChannel = null;
        }
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

    private boolean isNoBodyResponse(HttpResponse response) {
        int status = response.status().code();
        if ("HEAD".equalsIgnoreCase(method)
                || status == HttpResponseStatus.NO_CONTENT.code()
                || status == HttpResponseStatus.NOT_MODIFIED.code()) {
            return true;
        }
        if (HttpUtil.isTransferEncodingChunked(response)) {
            return false;
        }
        return HttpUtil.getContentLength(response, -1L) == 0L;
    }

    /**
     * Upstream 响应处理器，安装在 upstream channel 的 pipeline 上，
     * 将 upstream 的响应转发回 client。
     */
    private static class UpstreamResponseHandler
            extends ChannelInboundHandlerAdapter {

        private final ChannelHandlerContext clientCtx;
        private final ProxyHandler proxyHandler;

        UpstreamResponseHandler(ChannelHandlerContext clientCtx,
                ProxyHandler proxyHandler) {
            this.clientCtx = clientCtx;
            this.proxyHandler = proxyHandler;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (!clientCtx.channel().isActive()) {
                ReferenceCountUtil.release(msg);
                return;
            }
            if (msg instanceof HttpResponse response) {
                // 1xx 中间响应防御性丢弃（网关已自己回 100，上游不应再发）
                if (response.status().code() < 200) {
                    ReferenceCountUtil.release(msg);
                    return;
                }
                // upstream pipeline 无 HttpObjectAggregator，响应始终为分块模式
                proxyHandler.handleUpstreamResponse(clientCtx, response);
            } else if (msg instanceof HttpContent content) {
                proxyHandler.handleUpstreamContent(clientCtx, content);
            }
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx)
                throws Exception {
            // Upstream 断开连接
            if (clientCtx.channel().isActive()
                    && proxyHandler.responseStatusCode == 0) {
                // 还没收到响应就断开了
                log.warn("Upstream channel 提前断开，responseStatusCode=0，remoteAddr={}",
                        ctx.channel().remoteAddress());
                proxyHandler.sendErrorAndCleanup(clientCtx,
                        HttpResponseStatus.BAD_GATEWAY,
                        "Upstream connection closed prematurely");
            }
            super.channelInactive(ctx);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx,
                Throwable cause) {
            log.error("Upstream 响应处理异常，remoteAddr={}", ctx.channel().remoteAddress(), cause);
            if (clientCtx.channel().isActive()) {
                proxyHandler.sendErrorAndCleanup(clientCtx,
                        HttpResponseStatus.BAD_GATEWAY,
                        "Upstream error: " + cause.getMessage());
            }
        }
    }
}
