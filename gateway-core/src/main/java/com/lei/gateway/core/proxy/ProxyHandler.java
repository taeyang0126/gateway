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
import io.netty.handler.codec.http2.DefaultHttp2DataFrame;
import io.netty.handler.codec.http2.DefaultHttp2HeadersFrame;
import io.netty.handler.codec.http2.Http2FrameCodec;
import io.netty.handler.codec.http2.Http2FrameCodecAccess;
import io.netty.handler.codec.http2.Http2FrameStream;
import io.netty.handler.codec.http2.Http2Headers;
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
 * H2 模式的全流式转发处理器。
 *
 * <p>处理流程：borrow → 流控检查 → nextStreamId（溢出检测）→ register 映射 →
 * write HEADERS → 立即 requite → DATA 帧通过保存的 Channel 引用异步写入 → 响应通过映射表回调。
 *
 * <p>使用 {@link Http2FrameCodec} 高层帧 API（{@link DefaultHttp2HeadersFrame} /
 * {@link DefaultHttp2DataFrame}）写帧，由 codec 管理 stream 生命周期和 flow control。
 * stream ID 由 codec 自动分配（与 {@code ChannelPoolEntry.nextStreamId()} 同步递增），
 * 后者仅用于溢出检测。
 */
public class ProxyHandler extends ChannelInboundHandlerAdapter {

    private static final Logger log = LoggerFactory.getLogger(ProxyHandler.class);

    /** 流控重试上限，防止所有连接都满时无限循环 borrow。 */
    static final int MAX_ACQUIRE_RETRIES = 3;

    private final Route route;
    private final RequestLimitProperties limitConfig;
    private final UpstreamConnectionPool connectionPool;
    private final MetricsCollector metricsCollector;
    private final AccessLogWriter accessLogWriter;
    private final ObservabilityProperties observabilityConfig;
    private final InFlightRequestTracker inFlightTracker;

    // 请求级状态
    private long startTimeNanos;
    private String traceId;
    private String method;
    private String path;
    private String realClientIp;
    private String remoteClientIp;
    private Boolean authRequired;
    private Boolean authPassed;
    private String securityDecision;
    private String securityFilter;
    private String securityReason;
    private long requestBodySize;
    private long responseBodySize;
    private int responseStatusCode;
    private long maxRequestSize;

    // H2 模式状态
    private int streamId;
    private Channel h2Channel;
    private Http2FrameStream h2FrameStream;
    private H2ResponseDemuxHandler demuxHandler;
    private ChannelHandlerContext clientCtx;
    private int acquireRetryCount;

    private boolean connectingToUpstream;
    private Queue<HttpContent> pendingContent;
    private final AtomicBoolean requestCompleted = new AtomicBoolean(false);

    /** 创建 ProxyHandler。 */
    public ProxyHandler(Route route, ProxyContext ctx) {
        this.route = route;
        this.limitConfig = ctx.getRequestLimitProperties();
        this.connectionPool = ctx.getConnectionPool();
        this.metricsCollector = ctx.getMetricsCollector();
        this.accessLogWriter = ctx.getAccessLogWriter();
        this.observabilityConfig = ctx.getObservabilityProperties();
        this.inFlightTracker = ctx.getInFlightTracker();
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
        startTimeNanos = System.nanoTime();
        traceId = ctx.channel().attr(TraceContextHandler.TRACE_ID_KEY).get();

        method = request.method().name();
        path = request.uri();
        java.net.SocketAddress remoteAddr = ctx.channel().remoteAddress();
        remoteClientIp = resolveRemoteClientIp(remoteAddr);
        String resolvedClientIp = ctx.channel().attr(RoutingHandler.CLIENT_IP_KEY).get();
        if (resolvedClientIp != null && !resolvedClientIp.isBlank()) {
            realClientIp = resolvedClientIp;
        } else {
            realClientIp = remoteClientIp;
        }
        authRequired = ctx.channel().attr(RoutingHandler.AUTH_REQUIRED_KEY).get();
        authPassed = ctx.channel().attr(RoutingHandler.AUTH_PASSED_KEY).get();
        securityDecision = ctx.channel().attr(RoutingHandler.SECURITY_DECISION_KEY).get();
        securityFilter = ctx.channel().attr(RoutingHandler.SECURITY_FILTER_KEY).get();
        securityReason = ctx.channel().attr(RoutingHandler.SECURITY_REASON_KEY).get();

        boolean expectContinue = HttpUtil.is100ContinueExpected(request);

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

        if (expectContinue) {
            request.headers().remove(HttpHeaderNames.EXPECT);
        }

        URI upstreamUri;
        try {
            upstreamUri = URI.create(route.getUpstream());
        } catch (IllegalArgumentException ex) {
            log.error("无效的 upstream 地址: {}", route.getUpstream(), ex);
            sendErrorAndCleanup(ctx, HttpResponseStatus.BAD_GATEWAY,
                    "Invalid upstream address");
            return;
        }
        String upstreamHost = upstreamUri.getHost();
        int upstreamPort = upstreamUri.getPort() > 0
                ? upstreamUri.getPort() : 80;

        connectingToUpstream = true;
        this.clientCtx = ctx;

        HttpHeaders headers = request.headers();
        ProxyHeaderUtil.addProxyHeaders(headers, realClientIp, remoteClientIp,
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

        DefaultHttpRequest upstreamRequest = new DefaultHttpRequest(
                HttpVersion.HTTP_1_1, request.method(), request.uri(),
                request.headers());

        log.debug("traceId={} acquire upstream {}:{}", traceId,
                upstreamHost, upstreamPort);
        connectionPool.acquire(upstreamHost, upstreamPort)
                .whenComplete((channel, ex) -> {
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
            if (channel != null) {
                connectionPool.remove(channel);
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

        ChannelPoolEntry entry = channel.attr(ChannelPoolEntry.POOL_ENTRY_KEY).get();
        H2ResponseDemuxHandler demux = channel.pipeline().get(H2ResponseDemuxHandler.class);

        // 1. MAX_CONCURRENT_STREAMS 流控检查
        if (!demux.canCreateStream()) {
            connectionPool.release(channel);
            if (++acquireRetryCount > MAX_ACQUIRE_RETRIES) {
                log.warn("traceId={} 所有连接 MAX_CONCURRENT_STREAMS 已满，重试 {} 次后放弃",
                        traceId, MAX_ACQUIRE_RETRIES);
                sendErrorAndCleanup(ctx, HttpResponseStatus.SERVICE_UNAVAILABLE,
                        "All upstream connections at MAX_CONCURRENT_STREAMS limit");
                return;
            }
            reacquire(ctx, upstreamRequest);
            return;
        }

        // 2. 分配 streamId（溢出检查——与 codec 内部自增同步）
        int sid = entry.nextStreamId();
        if (sid == -1) {
            connectionPool.retire(channel);
            reacquire(ctx, upstreamRequest);
            return;
        }

        // 3. 通过高层帧 API 创建 Http2FrameStream
        Http2FrameCodec codec = (Http2FrameCodec) channel.pipeline()
                .get(Http2FrameCodec.class);
        Http2FrameStream frameStream = Http2FrameCodecAccess.newStream(codec);

        // 4. 保存引用
        this.h2Channel = channel;
        this.h2FrameStream = frameStream;
        this.demuxHandler = demux;
        connectingToUpstream = false;

        // 5. 构造 H2 HEADERS
        Http2Headers h2Headers = H2HeaderConverter.toH2Headers(upstreamRequest);
        boolean noBody = pendingContent == null || pendingContent.isEmpty();
        long contentLength = HttpUtil.getContentLength(upstreamRequest, -1L);
        boolean chunked = HttpUtil.isTransferEncodingChunked(upstreamRequest);
        boolean endStream = !chunked && contentLength <= 0 && noBody;

        // 6. 归还连接（独占结束）——在写帧之前归还，因为写帧会切到 H2 event loop
        connectionPool.release(channel);

        // 7. 帧写入必须在 H2 channel 的 event loop 上执行（codec 同步分配 stream ID）
        // 收集需要在 H2 event loop 上写入的 pending content
        Queue<HttpContent> buffered = null;
        if (!endStream && pendingContent != null) {
            buffered = pendingContent;
            pendingContent = null;
        }
        final Queue<HttpContent> pendingToWrite = buffered;

        channel.eventLoop().execute(() -> {
            // 写 HEADERS 帧——通过 channel.write() 让消息经过 Http2FrameCodec.write() 处理
            DefaultHttp2HeadersFrame headersFrame =
                    new DefaultHttp2HeadersFrame(h2Headers, endStream);
            headersFrame.stream(frameStream);
            channel.write(headersFrame).addListener(future -> {
                if (!future.isSuccess()) {
                    log.error("traceId={} 写 HEADERS 帧失败, streamId={}",
                            traceId, streamId, future.cause());
                    demux.remove(streamId);
                    ctx.channel().eventLoop().execute(() ->
                            sendErrorAndCleanup(ctx, HttpResponseStatus.BAD_GATEWAY,
                                    "Failed to write H2 HEADERS to upstream"));
                }
            });

            // codec 同步分配了 stream ID，获取并注册映射
            this.streamId = frameStream.id();
            log.debug("traceId={} acquired upstream={} streamId={}",
                    traceId, channel.id(), streamId);
            demux.register(streamId, this);

            // 写缓冲的请求体 DATA 帧
            if (pendingToWrite != null) {
                HttpContent content;
                while ((content = pendingToWrite.poll()) != null) {
                    writeDataFrame(content);
                }
            }
            channel.flush();
        });
    }

    /**
     * 重新获取 upstream 连接（流控超限或 streamId 溢出时调用）。
     */
    private void reacquire(ChannelHandlerContext ctx,
            DefaultHttpRequest upstreamRequest) {
        URI upstreamUri = URI.create(route.getUpstream());
        String host = upstreamUri.getHost();
        int port = upstreamUri.getPort() > 0 ? upstreamUri.getPort() : 80;
        connectionPool.acquire(host, port)
                .whenComplete((ch, err) -> {
                    if (ctx.channel().eventLoop().inEventLoop()) {
                        onAcquireComplete(ctx, upstreamRequest, ch, err);
                    } else {
                        ctx.channel().eventLoop().execute(() ->
                                onAcquireComplete(ctx, upstreamRequest, ch, err));
                    }
                });
    }

    private void handleHttpContent(ChannelHandlerContext ctx,
            HttpContent content) {
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

        if (connectingToUpstream) {
            if (pendingContent == null) {
                pendingContent = new ArrayDeque<>();
            }
            pendingContent.add(content);
            return;
        }

        if (h2Channel == null || !h2Channel.isActive()) {
            ReferenceCountUtil.release(content);
            return;
        }

        writeDataFrame(content);
    }

    /**
     * 将 H1 HttpContent 转换为 H2 DATA 帧，通过高层帧 API 写入。
     *
     * <p>必须在 H2 channel 的 event loop 上调用，或通过 {@code h2Channel.eventLoop().execute()} 调度。
     */
    private void writeDataFrame(HttpContent content) {
        boolean endStream = content instanceof LastHttpContent;
        ByteBuf data = content.content().retain();
        ReferenceCountUtil.release(content);
        DefaultHttp2DataFrame dataFrame = new DefaultHttp2DataFrame(data, endStream);
        dataFrame.stream(h2FrameStream);

        Runnable writeTask = () -> {
            h2Channel.write(dataFrame).addListener(future -> {
                if (!future.isSuccess()) {
                    log.error("traceId={} 写 DATA 帧失败, streamId={}",
                            traceId, streamId, future.cause());
                }
            });
            if (endStream) {
                h2Channel.flush();
            }
        };

        if (h2Channel.eventLoop().inEventLoop()) {
            writeTask.run();
        } else {
            h2Channel.eventLoop().execute(writeTask);
        }
    }

    // ---- H2 回调方法（由 H2ResponseDemuxHandler 调用）----

    /**
     * H2 响应头回调，由 {@link H2ResponseDemuxHandler} 在收到 HEADERS 帧时调用。
     *
     * @param response 转换后的 H1 响应对象
     */
    void onH2Response(HttpResponse response) {
        responseStatusCode = response.status().code();
        if (isNoBodyResponse(response)) {
            // no-body 响应直接写 FullHttpResponse 并完成请求。
            // H2ResponseDemuxHandler.handleHeaders() 在 END_STREAM 时仍会调用
            // onH2Content(EMPTY_LAST_CONTENT)，由 onH2Content 中的
            // requestCompleted 检查兜底忽略。
            if (demuxHandler != null && streamId > 0) {
                demuxHandler.remove(streamId);
            }
            FullHttpResponse fullResponse = new DefaultFullHttpResponse(
                    response.protocolVersion(), response.status(),
                    Unpooled.EMPTY_BUFFER, response.headers(),
                    io.netty.handler.codec.http.EmptyHttpHeaders.INSTANCE);
            clientCtx.writeAndFlush(fullResponse).addListener(future -> {
                if (!future.isSuccess()) {
                    log.error("写出响应头到客户端失败", future.cause());
                }
                completeRequest(clientCtx);
            });
            return;
        }
        long cl = HttpUtil.getContentLength(response, -1L);
        boolean isChunked = HttpUtil.isTransferEncodingChunked(response);
        if (!HttpUtil.isKeepAlive(response)
                || (!isChunked && cl < 0)) {
            response.headers().set(HttpHeaderNames.CONNECTION,
                    HttpHeaderValues.CLOSE);
            clientCtx.channel().attr(RoutingHandler.CONNECTION_CLOSE_KEY)
                    .set(Boolean.TRUE);
        }
        clientCtx.writeAndFlush(response);
    }

    /**
     * H2 响应体回调，由 {@link H2ResponseDemuxHandler} 在收到 DATA 帧时调用。
     *
     * @param content 转换后的 H1 内容块
     */
    void onH2Content(HttpContent content) {
        if (requestCompleted.get()) {
            ReferenceCountUtil.release(content);
            return;
        }
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

    /**
     * H2 错误回调，由 {@link H2ResponseDemuxHandler} 在 RST_STREAM/GOAWAY/连接断开时调用。
     *
     * @param cause 错误原因
     */
    void onH2Error(Throwable cause) {
        log.error("traceId={} H2 stream 错误, streamId={}", traceId, streamId, cause);
        if (clientCtx != null && clientCtx.channel().isActive()) {
            sendErrorAndCleanup(clientCtx, HttpResponseStatus.BAD_GATEWAY,
                    "Upstream H2 error");
        } else {
            cleanupOnError();
        }
    }

    private void completeRequest(ChannelHandlerContext ctx) {
        if (!requestCompleted.compareAndSet(false, true)) {
            log.debug("traceId={} completeRequest 重复调用，已忽略", traceId);
            return;
        }
        log.debug("traceId={} completeRequest: statusCode={}", traceId,
                responseStatusCode);
        inFlightTracker.decrement();
        long durationNanos = System.nanoTime() - startTimeNanos;

        metricsCollector.recordRequest(method, path, responseStatusCode,
                durationNanos);

        AccessLogEntry logEntry = new AccessLogEntry();
        logEntry.setMethod(method);
        logEntry.setPath(path);
        logEntry.setStatusCode(responseStatusCode);
        logEntry.setDurationMs(durationNanos / 1_000_000);
        logEntry.setClientIp(realClientIp);
        logEntry.setUpstream(route.getUpstream());
        logEntry.setRequestBodySize(requestBodySize);
        logEntry.setResponseBodySize(responseBodySize);
        logEntry.setTraceId(traceId);
        logEntry.setAuthRequired(authRequired);
        logEntry.setAuthPassed(authPassed);
        logEntry.setSecurityDecision(securityDecision);
        logEntry.setSecurityFilter(securityFilter);
        logEntry.setSecurityReason(securityReason);
        accessLogWriter.log(logEntry);

        releasePendingContent();
        RoutingHandler.onProxyComplete(ctx);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        boolean wasNotCompleted = requestCompleted.compareAndSet(false, true);
        if (wasNotCompleted) {
            inFlightTracker.decrement();
        }
        if (demuxHandler != null && streamId > 0) {
            demuxHandler.remove(streamId);
        }
        releasePendingContent();
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
        cleanupOnError();
        if (ctx.channel().isActive()) {
            sendError(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR,
                    "Internal server error");
        }
    }

    private void sendErrorAndCleanup(ChannelHandlerContext ctx,
            HttpResponseStatus status, String message) {
        cleanupOnError();
        sendError(ctx, status, message);
        RoutingHandler.onProxyComplete(ctx);
    }

    private void cleanupOnError() {
        boolean wasNotCompleted = requestCompleted.compareAndSet(false, true);
        if (wasNotCompleted) {
            inFlightTracker.decrement();
        }
        if (demuxHandler != null && streamId > 0) {
            demuxHandler.remove(streamId);
        }
        releasePendingContent();
    }

    private void releasePendingContent() {
        connectingToUpstream = false;
        if (pendingContent != null) {
            HttpContent buffered;
            while ((buffered = pendingContent.poll()) != null) {
                ReferenceCountUtil.release(buffered);
            }
            pendingContent = null;
        }
    }

    private void sendError(ChannelHandlerContext ctx,
            HttpResponseStatus status, String message) {
        responseStatusCode = status.code();
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

    private static String resolveRemoteClientIp(java.net.SocketAddress remoteAddr) {
        if (remoteAddr instanceof InetSocketAddress inetAddr) {
            return inetAddr.getAddress().getHostAddress();
        }
        return remoteAddr != null ? remoteAddr.toString() : "unknown";
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
}
