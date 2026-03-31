package com.lei.gateway.core.proxy;

import com.lei.gateway.core.config.HealthProperties;
import com.lei.gateway.core.config.ObservabilityProperties;
import com.lei.gateway.core.config.RequestLimitProperties;
import com.lei.gateway.core.config.Route;
import com.lei.gateway.core.config.RouteResolver;
import com.lei.gateway.core.observability.AccessLogEntry;
import com.lei.gateway.core.observability.AccessLogWriter;
import com.lei.gateway.core.observability.MetricsCollector;
import com.lei.gateway.core.observability.TraceContextHandler;
import com.lei.gateway.core.plugin.GatewayPluginProcessor;
import com.lei.gateway.core.plugin.PluginContext;
import com.lei.gateway.core.plugin.PluginExecutionResult;
import com.lei.gateway.core.plugin.PluginResult;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.util.AttributeKey;
import io.netty.util.CharsetUtil;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 路由匹配与请求分发处理器。
 *
 * <p>拦截 HttpRequest，根据路径前缀匹配路由规则，
 * 处理 /health 和 /metrics 端点，动态添加 ProxyHandler。
 *
 * <p>连接级状态（如 Connection: close 标记）存储在 Channel Attribute 中，
 * RoutingHandler 本身保持无状态可共享。
 */
@ChannelHandler.Sharable
public class RoutingHandler extends ChannelInboundHandlerAdapter {

    private static final Logger log = LoggerFactory.getLogger(RoutingHandler.class);

    /** Channel Attribute：标记当前连接是否需要在响应后关闭。 */
    static final AttributeKey<Boolean> CONNECTION_CLOSE_KEY =
            AttributeKey.valueOf("connectionClose");
    static final AttributeKey<String> CLIENT_IP_KEY =
            AttributeKey.valueOf("clientIp");
    static final AttributeKey<Boolean> AUTH_REQUIRED_KEY =
            AttributeKey.valueOf("authRequired");
    static final AttributeKey<Boolean> AUTH_PASSED_KEY =
            AttributeKey.valueOf("authPassed");
    static final AttributeKey<String> SECURITY_DECISION_KEY =
            AttributeKey.valueOf("securityDecision");
    static final AttributeKey<String> SECURITY_FILTER_KEY =
            AttributeKey.valueOf("securityFilter");
    static final AttributeKey<String> SECURITY_REASON_KEY =
            AttributeKey.valueOf("securityReason");

    private static final String HEALTH_PATH = "/health";
    private static final String HEALTH_LIVE_PATH = "/health/live";
    private static final String HEALTH_READY_PATH = "/health/ready";
    private static final String METRICS_PATH = "/metrics";
    private static final String PROXY_HANDLER_NAME = "proxy";
    private static final String CONTENT_TYPE_JSON = "application/json";
    private static final String CONTENT_TYPE_PROMETHEUS =
            "text/plain; version=0.0.4; charset=utf-8";

    private final RouteResolver routeResolver;
    private final RequestLimitProperties requestLimitProperties;
    private final UpstreamConnectionPool connectionPool;
    private final MetricsCollector metricsCollector;
    private final AccessLogWriter accessLogWriter;
    private final ObservabilityProperties observabilityProperties;
    private final GatewayPluginProcessor pluginProcessor;
    private final InFlightRequestTracker inFlightTracker;
    private final DrainHandler drainHandler;
    private final HealthProperties healthProperties;
    private final AtomicInteger activeConnections;
    private final Instant startTime;

    /** 创建 RoutingHandler。 */
    public RoutingHandler(RoutingContext ctx,
            AtomicInteger activeConnections,
            Instant startTime) {
        this.routeResolver = ctx.getRouteResolver();
        this.requestLimitProperties = ctx.getRequestLimitProperties();
        this.connectionPool = ctx.getConnectionPool();
        this.metricsCollector = ctx.getMetricsCollector();
        this.accessLogWriter = ctx.getAccessLogWriter();
        this.observabilityProperties = ctx.getObservabilityProperties();
        this.pluginProcessor = ctx.getPluginProcessor();
        this.inFlightTracker = ctx.getInFlightTracker();
        this.drainHandler = ctx.getDrainHandler();
        this.healthProperties = ctx.getHealthProperties();
        this.activeConnections = activeConnections;
        this.startTime = startTime;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        activeConnections.incrementAndGet();
        super.channelActive(ctx);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        activeConnections.decrementAndGet();
        super.channelInactive(ctx);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg)
            throws Exception {
        if (!(msg instanceof HttpRequest request)) {
            ctx.fireChannelRead(msg);
            return;
        }

        // 记录 Connection: close 标记
        boolean keepAlive = HttpUtil.isKeepAlive(request);
        ctx.channel().attr(CONNECTION_CLOSE_KEY).set(!keepAlive);
        final long requestStartNanos = System.nanoTime();

        String path = new QueryStringDecoder(request.uri()).path();

        // /health/live 端点
        if (HEALTH_LIVE_PATH.equals(path)
                && HttpMethod.GET.equals(request.method())) {
            handleHealthLive(ctx, request);
            return;
        }

        // /health/ready 端点
        if (HEALTH_READY_PATH.equals(path)
                && HttpMethod.GET.equals(request.method())) {
            handleHealthReady(ctx, request);
            return;
        }

        // /health 端点
        if (HEALTH_PATH.equals(path) && HttpMethod.GET.equals(request.method())) {
            handleHealth(ctx, request);
            return;
        }

        // /metrics 端点
        if (METRICS_PATH.equals(path) && HttpMethod.GET.equals(request.method())) {
            handleMetrics(ctx, request);
            return;
        }

        // 路由匹配
        Optional<Route> matched = routeResolver.resolve(path);
        if (matched.isEmpty()) {
            sendError(ctx, request, HttpResponseStatus.NOT_FOUND,
                    "No route matched for path: " + path);
            return;
        }

        // 动态添加 ProxyHandler，先检查是否已存在（防止 keep-alive 连接上重复添加）
        if (ctx.pipeline().get(PROXY_HANDLER_NAME) != null) {
            log.warn("ProxyHandler 已存在于 pipeline，忽略本次请求: {}", path);
            return;
        }
        Route route = matched.get();
        String traceId = ctx.channel().attr(TraceContextHandler.TRACE_ID_KEY).get();

        PluginExecutionResult execResult = pluginProcessor.executeRequestPhase(
                ctx, request, route, traceId);
        PluginContext pluginCtx = execResult.getContext();

        attachPluginAttributes(ctx, execResult);
        if (pluginCtx.getClientIp() != null) {
            ctx.channel().attr(CLIENT_IP_KEY).set(pluginCtx.getClientIp());
        }
        if (observabilityProperties.isTracingEnabled()) {
            ctx.channel().attr(TraceContextHandler.TRACE_SECURITY_TAGS_KEY).set(
                    pluginCtx.getTraceTags());
        }
        if (!execResult.isContinue()) {
            writePluginAccessLog(ctx, request, route, execResult, requestStartNanos);
            sendPluginResponse(ctx, request, execResult.getResult());
            return;
        }

        ProxyContext proxyCtx = new ProxyContext(requestLimitProperties,
                connectionPool, metricsCollector, accessLogWriter,
                observabilityProperties, inFlightTracker);
        ProxyHandler proxyHandler = new ProxyHandler(route, proxyCtx);
        ctx.pipeline().addAfter("routing", PROXY_HANDLER_NAME, proxyHandler);
        inFlightTracker.increment();
        ctx.fireChannelRead(msg);
    }

    /**
     * ProxyHandler 完成后调用此方法，从 Pipeline 移除 ProxyHandler，
     * 重置状态准备处理同一连接上的下一个请求。
     *
     * @param ctx ChannelHandlerContext
     */
    public static void onProxyComplete(ChannelHandlerContext ctx) {
        if (ctx.pipeline().get(PROXY_HANDLER_NAME) != null) {
            ctx.pipeline().remove(PROXY_HANDLER_NAME);
        }
        Boolean shouldClose = ctx.channel().attr(CONNECTION_CLOSE_KEY).get();
        if (Boolean.TRUE.equals(shouldClose)) {
            ctx.close();
        }
    }

    private void handleHealth(ChannelHandlerContext ctx, HttpRequest request) {
        String json = "{\"status\":\"UP\",\"startTime\":\""
                + startTime.toString()
                + "\",\"activeConnections\":" + activeConnections.get() + "}";
        sendResponse(ctx, request, HttpResponseStatus.OK, json, CONTENT_TYPE_JSON,
                null);
    }

    private void handleHealthLive(ChannelHandlerContext ctx,
            HttpRequest request) {
        sendResponse(ctx, request, HttpResponseStatus.OK,
                "{\"status\":\"UP\"}", CONTENT_TYPE_JSON, null);
    }

    private void handleHealthReady(ChannelHandlerContext ctx,
            HttpRequest request) {
        if (drainHandler.isDraining()) {
            sendResponse(ctx, request,
                    HttpResponseStatus.SERVICE_UNAVAILABLE,
                    "{\"status\":\"DOWN\",\"reason\":\"draining\"}",
                    CONTENT_TYPE_JSON, null);
            return;
        }
        int delaySeconds = healthProperties.getStartupDelaySeconds();
        if (delaySeconds > 0
                && Instant.now().isBefore(
                        startTime.plusSeconds(delaySeconds))) {
            sendResponse(ctx, request,
                    HttpResponseStatus.SERVICE_UNAVAILABLE,
                    "{\"status\":\"DOWN\",\"reason\":\"warming_up\"}",
                    CONTENT_TYPE_JSON, null);
            return;
        }
        sendResponse(ctx, request, HttpResponseStatus.OK,
                "{\"status\":\"UP\"}", CONTENT_TYPE_JSON, null);
    }

    private void handleMetrics(ChannelHandlerContext ctx, HttpRequest request) {
        if (!observabilityProperties.isMetricsEnabled()) {
            sendError(ctx, request, HttpResponseStatus.NOT_FOUND,
                    "Metrics endpoint is disabled");
            return;
        }
        String body = metricsCollector.scrape();
        sendResponse(ctx, request, HttpResponseStatus.OK, body,
                CONTENT_TYPE_PROMETHEUS, null);
    }

    static void sendError(ChannelHandlerContext ctx, HttpRequest request,
            HttpResponseStatus status, String message) {
        String json = "{\"status\":" + status.code()
                + ",\"error\":\"" + status.reasonPhrase()
                + "\",\"message\":\"" + escapeJson(message) + "\"}";
        sendResponse(ctx, request, status, json, CONTENT_TYPE_JSON, null);
    }

    private static void sendResponse(ChannelHandlerContext ctx,
            HttpRequest request, HttpResponseStatus status,
            String body, String contentType, Integer retryAfterSeconds) {
        ByteBuf content = Unpooled.copiedBuffer(body, CharsetUtil.UTF_8);
        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, status, content);
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, contentType);
        response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH,
                content.readableBytes());
        if (retryAfterSeconds != null) {
            response.headers().set("Retry-After", retryAfterSeconds);
        }

        boolean keepAlive = HttpUtil.isKeepAlive(request);
        if (keepAlive) {
            response.headers().set(HttpHeaderNames.CONNECTION,
                    HttpHeaderValues.KEEP_ALIVE);
            ctx.writeAndFlush(response);
        } else {
            response.headers().set(HttpHeaderNames.CONNECTION,
                    HttpHeaderValues.CLOSE);
            ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
        }
    }

    private static void sendPluginResponse(ChannelHandlerContext ctx,
            HttpRequest request, PluginResult result) {
        String message = "Rejected by " + result.getPluginName()
                + ": " + result.getReason();
        String json = "{\"status\":" + result.getStatus().code()
                + ",\"error\":\"" + result.getStatus().reasonPhrase()
                + "\",\"message\":\"" + escapeJson(message) + "\"}";
        sendResponse(ctx, request, result.getStatus(), json, CONTENT_TYPE_JSON,
                result.getRetryAfterSeconds());
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

    private static void attachPluginAttributes(ChannelHandlerContext ctx,
            PluginExecutionResult execResult) {
        PluginResult result = execResult.getResult();
        PluginContext pluginCtx = execResult.getContext();
        Boolean authRequired = pluginCtx.getAttribute("authRequired", Boolean.class);
        Boolean authPassed = pluginCtx.getAttribute("authPassed", Boolean.class);
        ctx.channel().attr(AUTH_REQUIRED_KEY).set(authRequired);
        ctx.channel().attr(AUTH_PASSED_KEY).set(authPassed);
        String decision = result.isContinue() ? "ALLOW" : "DENY";
        ctx.channel().attr(SECURITY_DECISION_KEY).set(decision);
        ctx.channel().attr(SECURITY_FILTER_KEY).set(result.getPluginName());
        ctx.channel().attr(SECURITY_REASON_KEY).set(result.getReason());
    }

    private void writePluginAccessLog(ChannelHandlerContext ctx,
            HttpRequest request, Route route,
            PluginExecutionResult execResult, long requestStartNanos) {
        long durationNanos = System.nanoTime() - requestStartNanos;
        AccessLogEntry logEntry = new AccessLogEntry();
        logEntry.setMethod(request.method().name());
        logEntry.setPath(request.uri());
        logEntry.setStatusCode(execResult.getResult().getStatus().code());
        logEntry.setDurationMs(durationNanos / 1_000_000);
        String resolvedClientIp = execResult.getContext().getClientIp();
        if (resolvedClientIp == null || resolvedClientIp.isBlank()) {
            resolvedClientIp = resolveRemoteClientIp(ctx.channel().remoteAddress());
        }
        logEntry.setClientIp(resolvedClientIp);
        logEntry.setUpstream(route.getUpstream());
        logEntry.setRequestBodySize(0);
        logEntry.setResponseBodySize(0);
        logEntry.setTraceId(ctx.channel().attr(TraceContextHandler.TRACE_ID_KEY).get());
        logEntry.setAuthRequired(ctx.channel().attr(AUTH_REQUIRED_KEY).get());
        logEntry.setAuthPassed(ctx.channel().attr(AUTH_PASSED_KEY).get());
        logEntry.setSecurityDecision(ctx.channel().attr(SECURITY_DECISION_KEY).get());
        logEntry.setSecurityFilter(ctx.channel().attr(SECURITY_FILTER_KEY).get());
        logEntry.setSecurityReason(ctx.channel().attr(SECURITY_REASON_KEY).get());
        accessLogWriter.log(logEntry);
    }

    private static String resolveRemoteClientIp(SocketAddress remoteAddr) {
        if (remoteAddr instanceof InetSocketAddress inet) {
            if (inet.getAddress() != null) {
                return inet.getAddress().getHostAddress();
            }
            return inet.getHostString();
        }
        return remoteAddr == null ? "unknown" : remoteAddr.toString();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("RoutingHandler 异常", cause);
        if (ctx.channel().isActive()) {
            ByteBuf content = Unpooled.copiedBuffer(
                    "{\"status\":500,\"error\":\"Internal Server Error\","
                    + "\"message\":\"Internal server error\"}",
                    CharsetUtil.UTF_8);
            FullHttpResponse response = new DefaultFullHttpResponse(
                    HttpVersion.HTTP_1_1,
                    HttpResponseStatus.INTERNAL_SERVER_ERROR, content);
            response.headers().set(HttpHeaderNames.CONTENT_TYPE,
                    CONTENT_TYPE_JSON);
            response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH,
                    content.readableBytes());
            response.headers().set(HttpHeaderNames.CONNECTION,
                    HttpHeaderValues.CLOSE);
            ctx.writeAndFlush(response)
                    .addListener(ChannelFutureListener.CLOSE);
        }
    }
}
