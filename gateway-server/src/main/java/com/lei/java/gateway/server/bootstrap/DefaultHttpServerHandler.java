/*
 * Copyright (c) 2026 lei.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.lei.java.gateway.server.bootstrap;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.channels.ClosedChannelException;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.lei.java.gateway.server.config.RouteConfig;
import com.lei.java.gateway.server.config.UpstreamConfig;
import com.lei.java.gateway.server.http.DefaultErrorResponseMapper;
import com.lei.java.gateway.server.http.DefaultHeaderPolicyService;
import com.lei.java.gateway.server.http.ErrorResponse;
import com.lei.java.gateway.server.http.ErrorResponseMapper;
import com.lei.java.gateway.server.http.HeaderPolicyService;
import com.lei.java.gateway.server.metrics.GatewayMetricsService;
import com.lei.java.gateway.server.metrics.NoopGatewayMetricsService;
import com.lei.java.gateway.server.proxy.DefaultTimeoutPolicy;
import com.lei.java.gateway.server.proxy.TimeoutPolicy;
import com.lei.java.gateway.server.routing.RouteService;
import com.lei.java.gateway.server.routing.StaticRouteService;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import io.netty.util.ReferenceCountUtil;

final class DefaultHttpServerHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultHttpServerHandler.class);
    private static final CharSequence TRACE_ID_HEADER = "X-Trace-Id";
    private static final String HEALTH_PATH = "/health";
    private static final String METRICS_PATH = "/metrics/prometheus";
    private static final String CONTENT_TYPE_TEXT = "text/plain; charset=UTF-8";
    private static final String CONTENT_TYPE_JSON = "application/json; charset=UTF-8";
    private static final String CONTENT_TYPE_PROMETHEUS =
            "text/plain; version=0.0.4; charset=UTF-8";
    private static final byte[] HEALTH_BODY = "OK".getBytes(StandardCharsets.UTF_8);
    private static final String ROUTE_ID_LOCAL_METRICS = "local_metrics";
    private static final String ROUTE_ID_LOCAL_HEALTH = "local_health";
    private static final String ERROR_CODE_MANAGEMENT_ENDPOINT_FORBIDDEN =
            "MANAGEMENT_ENDPOINT_FORBIDDEN";
    private static final String ERROR_CODE_UPSTREAM_BACKLOG_OVERFLOW = "UPSTREAM_BACKLOG_OVERFLOW";
    private static final List<CharSequence> HOP_BY_HOP_HEADERS =
            List.of(
                    HttpHeaderNames.CONNECTION,
                    "Keep-Alive",
                    HttpHeaderNames.TE,
                    HttpHeaderNames.TRAILER,
                    HttpHeaderNames.UPGRADE,
                    "Proxy-Authenticate",
                    "Proxy-Authorization",
                    HttpHeaderNames.TRANSFER_ENCODING);

    private final int maxContentLength;
    private final List<RouteConfig> routes;
    private final RouteService routeService;
    private final HeaderPolicyService headerPolicyService;
    private final TimeoutPolicy timeoutPolicy;
    private final ErrorResponseMapper errorResponseMapper;
    private final GatewayMetricsService gatewayMetricsService;
    private final boolean healthEndpointEnabled;
    private final boolean metricsEndpointEnabled;
    private final Set<String> managementAllowedClientIps;
    private final int maxPendingPerRoute;
    private final Map<String, UpstreamRouteClient> upstreamClients;

    DefaultHttpServerHandler(final int maxContentLength, final List<RouteConfig> routes) {
        this(
                maxContentLength,
                routes,
                new StaticRouteService(),
                new DefaultHeaderPolicyService(),
                new DefaultTimeoutPolicy(),
                new DefaultErrorResponseMapper(),
                new NoopGatewayMetricsService(),
                true,
                true,
                List.of("127.0.0.1", "::1", "0:0:0:0:0:0:0:1"),
                1024);
    }

    DefaultHttpServerHandler(
            final int maxContentLength,
            final List<RouteConfig> routes,
            final RouteService routeService,
            final HeaderPolicyService headerPolicyService,
            final TimeoutPolicy timeoutPolicy,
            final ErrorResponseMapper errorResponseMapper,
            final GatewayMetricsService gatewayMetricsService,
            final boolean healthEndpointEnabled,
            final boolean metricsEndpointEnabled,
            final List<String> managementAllowedClientIps,
            final int maxPendingPerRoute) {
        this.maxContentLength = maxContentLength;
        this.routes = List.copyOf(Objects.requireNonNull(routes, "routes must not be null"));
        this.routeService = Objects.requireNonNull(routeService, "routeService must not be null");
        this.headerPolicyService =
                Objects.requireNonNull(headerPolicyService, "headerPolicyService must not be null");
        this.timeoutPolicy =
                Objects.requireNonNull(timeoutPolicy, "timeoutPolicy must not be null");
        this.errorResponseMapper =
                Objects.requireNonNull(errorResponseMapper, "errorResponseMapper must not be null");
        this.gatewayMetricsService =
                Objects.requireNonNull(
                        gatewayMetricsService, "gatewayMetricsService must not be null");
        this.healthEndpointEnabled = healthEndpointEnabled;
        this.metricsEndpointEnabled = metricsEndpointEnabled;
        this.managementAllowedClientIps =
                normalizeManagementAllowedClientIps(managementAllowedClientIps);
        this.maxPendingPerRoute = maxPendingPerRoute;
        this.upstreamClients = new HashMap<>();
    }

    @Override
    protected void channelRead0(
            final ChannelHandlerContext context, final FullHttpRequest request) {
        gatewayMetricsService.onInboundStart();
        final boolean keepAlive = HttpUtil.isKeepAlive(request);
        final String traceId = resolveTraceId(request.headers());
        final ProxyRequest proxyRequest =
                createProxyRequest(request, keepAlive, resolveClientIp(context), traceId);

        final QueryStringDecoder decoder = new QueryStringDecoder(request.uri());
        final String requestPath = decoder.path();
        final RequestDispatchTarget target = resolveDispatchTarget(requestPath);
        switch (target) {
            case MetricsTarget ignored -> {
                if (!isManagementClientAllowed(proxyRequest.clientIp())) {
                    handleManagementForbiddenRequest(context, proxyRequest, ROUTE_ID_LOCAL_METRICS);
                    return;
                }
                handleMetricsRequest(context, proxyRequest);
            }
            case HealthTarget ignored -> {
                if (!isManagementClientAllowed(proxyRequest.clientIp())) {
                    handleManagementForbiddenRequest(context, proxyRequest, ROUTE_ID_LOCAL_HEALTH);
                    return;
                }
                handleHealthRequest(context, proxyRequest);
            }
            case NotFoundTarget ignored -> handleNotFoundRequest(context, proxyRequest);
            case RoutedTarget routedTarget ->
                    forwardRequest(context, proxyRequest, routedTarget.route());
        }
    }

    @Override
    public void exceptionCaught(final ChannelHandlerContext context, final Throwable cause) {
        final String traceId = UUID.randomUUID().toString().replace("-", "");
        final ErrorResponse errorResponse = errorResponseMapper.map(cause, traceId);
        final byte[] body = errorResponse.toJson().getBytes(StandardCharsets.UTF_8);
        writeErrorResponse(context, false, errorResponse);
        logAccessFailure(
                traceId,
                "-",
                "UNKNOWN",
                errorResponse.status(),
                0,
                "internal_error",
                "-",
                errorResponse.code());
        gatewayMetricsService.onInboundComplete(
                "internal_error",
                "UNKNOWN",
                errorResponse.status(),
                0,
                false,
                errorResponse.code(),
                0,
                body.length);
    }

    @Override
    public void channelInactive(final ChannelHandlerContext context) {
        closeUpstreamClients();
        context.fireChannelInactive();
    }

    private void forwardRequest(
            final ChannelHandlerContext context,
            final ProxyRequest request,
            final RouteConfig route) {
        final String routeId = route.routeId();
        final String upstreamAddress = buildUpstreamAddress(route);
        final UpstreamRouteClient routeClient =
                upstreamClients.computeIfAbsent(
                        routeId,
                        ignored -> new UpstreamRouteClient(context, route, upstreamAddress));
        routeClient.submit(request);
    }

    private void handleMetricsRequest(
            final ChannelHandlerContext context, final ProxyRequest proxyRequest) {
        final byte[] body = gatewayMetricsService.scrape().getBytes(StandardCharsets.UTF_8);
        writePlainTextResponse(
                context,
                proxyRequest.keepAlive(),
                HttpResponseStatus.OK,
                body,
                proxyRequest.traceId(),
                CONTENT_TYPE_PROMETHEUS);
        gatewayMetricsService.onInboundComplete(
                ROUTE_ID_LOCAL_METRICS,
                proxyRequest.method().name(),
                HttpResponseStatus.OK.code(),
                latencyMs(proxyRequest.startTimeNanos()),
                true,
                "-",
                proxyRequest.body().length,
                body.length);
    }

    private void handleHealthRequest(
            final ChannelHandlerContext context, final ProxyRequest proxyRequest) {
        writePlainTextResponse(
                context,
                proxyRequest.keepAlive(),
                HttpResponseStatus.OK,
                HEALTH_BODY,
                proxyRequest.traceId(),
                CONTENT_TYPE_TEXT);
        logAccessSuccess(
                proxyRequest.traceId(),
                proxyRequest.uri(),
                proxyRequest.method().name(),
                HttpResponseStatus.OK.code(),
                latencyMs(proxyRequest.startTimeNanos()),
                ROUTE_ID_LOCAL_HEALTH,
                "local://health");
        gatewayMetricsService.onInboundComplete(
                ROUTE_ID_LOCAL_HEALTH,
                proxyRequest.method().name(),
                HttpResponseStatus.OK.code(),
                latencyMs(proxyRequest.startTimeNanos()),
                true,
                "-",
                proxyRequest.body().length,
                HEALTH_BODY.length);
    }

    private void handleManagementForbiddenRequest(
            final ChannelHandlerContext context,
            final ProxyRequest proxyRequest,
            final String routeId) {
        final ErrorResponse errorResponse =
                new ErrorResponse(
                        OffsetDateTime.now(ZoneOffset.UTC).toString(),
                        proxyRequest.traceId(),
                        "GATEWAY_ERROR",
                        ERROR_CODE_MANAGEMENT_ENDPOINT_FORBIDDEN,
                        "management endpoint forbidden",
                        HttpResponseStatus.FORBIDDEN.code());
        final byte[] responseBodyBytes = errorResponse.toJson().getBytes(StandardCharsets.UTF_8);
        writeErrorResponse(context, proxyRequest.keepAlive(), errorResponse);
        logAccessFailure(
                proxyRequest.traceId(),
                proxyRequest.uri(),
                proxyRequest.method().name(),
                HttpResponseStatus.FORBIDDEN.code(),
                latencyMs(proxyRequest.startTimeNanos()),
                routeId,
                "local://management",
                ERROR_CODE_MANAGEMENT_ENDPOINT_FORBIDDEN);
        gatewayMetricsService.onInboundComplete(
                routeId,
                proxyRequest.method().name(),
                HttpResponseStatus.FORBIDDEN.code(),
                latencyMs(proxyRequest.startTimeNanos()),
                false,
                ERROR_CODE_MANAGEMENT_ENDPOINT_FORBIDDEN,
                proxyRequest.body().length,
                responseBodyBytes.length);
    }

    private void handleNotFoundRequest(
            final ChannelHandlerContext context, final ProxyRequest proxyRequest) {
        final ErrorResponse errorResponse =
                new ErrorResponse(
                        OffsetDateTime.now(ZoneOffset.UTC).toString(),
                        proxyRequest.traceId(),
                        "GATEWAY_ERROR",
                        "ROUTE_NOT_FOUND",
                        "route not found",
                        HttpResponseStatus.NOT_FOUND.code());
        final byte[] responseBodyBytes = errorResponse.toJson().getBytes(StandardCharsets.UTF_8);
        writeErrorResponse(context, proxyRequest.keepAlive(), errorResponse);
        logAccessFailure(
                proxyRequest.traceId(),
                proxyRequest.uri(),
                proxyRequest.method().name(),
                HttpResponseStatus.NOT_FOUND.code(),
                latencyMs(proxyRequest.startTimeNanos()),
                "route_not_found",
                "-",
                "ROUTE_NOT_FOUND");
        gatewayMetricsService.onInboundComplete(
                "route_not_found",
                proxyRequest.method().name(),
                HttpResponseStatus.NOT_FOUND.code(),
                latencyMs(proxyRequest.startTimeNanos()),
                false,
                "ROUTE_NOT_FOUND",
                proxyRequest.body().length,
                responseBodyBytes.length);
    }

    private RequestDispatchTarget resolveDispatchTarget(final String requestPath) {
        if (metricsEndpointEnabled && METRICS_PATH.equals(requestPath)) {
            return new MetricsTarget();
        }
        if (healthEndpointEnabled && HEALTH_PATH.equals(requestPath)) {
            return new HealthTarget();
        }
        final Optional<RouteConfig> route = routeService.select(requestPath, routes);
        return route.<RequestDispatchTarget>map(RoutedTarget::new).orElseGet(NotFoundTarget::new);
    }

    private static Set<String> normalizeManagementAllowedClientIps(final List<String> clientIps) {
        final List<String> nonNullClientIps =
                Objects.requireNonNull(clientIps, "managementAllowedClientIps must not be null");
        final Set<String> normalized = new HashSet<>();
        for (String clientIp : nonNullClientIps) {
            if (clientIp != null && !clientIp.isBlank()) {
                normalized.add(clientIp.trim());
            }
        }
        return Set.copyOf(normalized);
    }

    private boolean isManagementClientAllowed(final String clientIp) {
        return managementAllowedClientIps.contains("*")
                || managementAllowedClientIps.contains(clientIp);
    }

    private FullHttpRequest buildOutboundRequest(
            final ProxyRequest inboundRequest, final RouteConfig route) {
        final ByteBuf body = Unpooled.wrappedBuffer(inboundRequest.body());
        final FullHttpRequest outboundRequest =
                new DefaultFullHttpRequest(
                        HttpVersion.HTTP_1_1, inboundRequest.method(), inboundRequest.uri(), body);

        headerPolicyService.applyRequestHeaders(
                inboundRequest.headers(),
                outboundRequest.headers(),
                route,
                inboundRequest.clientIp(),
                inboundRequest.traceId());
        HttpUtil.setContentLength(outboundRequest, body.readableBytes());
        return outboundRequest;
    }

    private void writeMappedError(
            final ChannelHandlerContext context,
            final PendingExchange exchange,
            final String routeId,
            final String upstreamAddress,
            final Throwable throwable) {
        final ProxyRequest request = exchange.request();
        final ErrorResponse errorResponse =
                errorResponseMapper.map(
                        Objects.requireNonNullElseGet(
                                throwable,
                                () -> new IllegalStateException("unknown upstream error")),
                        request.traceId());
        final byte[] responseBodyBytes = errorResponse.toJson().getBytes(StandardCharsets.UTF_8);
        if (context.channel().isActive()) {
            writeErrorResponse(context, request.keepAlive(), errorResponse);
        }
        logAccessFailure(
                request.traceId(),
                request.uri(),
                request.method().name(),
                errorResponse.status(),
                latencyMs(request.startTimeNanos()),
                routeId,
                upstreamAddress,
                errorResponse.code());
        gatewayMetricsService.onUpstreamComplete(
                routeId,
                errorResponse.status(),
                latencyMs(exchange.upstreamStartTimeNanos()),
                false,
                errorResponse.code(),
                request.body().length,
                responseBodyBytes.length);
        gatewayMetricsService.onInboundComplete(
                routeId,
                request.method().name(),
                errorResponse.status(),
                latencyMs(request.startTimeNanos()),
                false,
                errorResponse.code(),
                request.body().length,
                responseBodyBytes.length);
    }

    private void closeUpstreamClients() {
        for (UpstreamRouteClient client : upstreamClients.values()) {
            client.close();
        }
        upstreamClients.clear();
    }

    private static void writePlainTextResponse(
            final ChannelHandlerContext context,
            final boolean keepAlive,
            final HttpResponseStatus status,
            final byte[] body,
            final String traceId,
            final String contentType) {
        final FullHttpResponse response =
                new DefaultFullHttpResponse(
                        HttpVersion.HTTP_1_1, status, Unpooled.wrappedBuffer(body));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, contentType);
        HttpUtil.setContentLength(response, body.length);
        writeTraceId(response.headers(), traceId);
        writeToInbound(context, keepAlive, response);
    }

    private static void writeErrorResponse(
            final ChannelHandlerContext context,
            final boolean keepAlive,
            final ErrorResponse errorResponse) {
        final byte[] body = errorResponse.toJson().getBytes(StandardCharsets.UTF_8);
        final FullHttpResponse response =
                new DefaultFullHttpResponse(
                        HttpVersion.HTTP_1_1,
                        HttpResponseStatus.valueOf(errorResponse.status()),
                        Unpooled.wrappedBuffer(body));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, CONTENT_TYPE_JSON);
        HttpUtil.setContentLength(response, body.length);
        writeTraceId(response.headers(), errorResponse.traceId());
        writeToInbound(context, keepAlive, response);
    }

    private static void writeToInbound(
            final ChannelHandlerContext context,
            final boolean keepAlive,
            final FullHttpResponse response) {
        if (keepAlive) {
            response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
            context.writeAndFlush(response);
            return;
        }
        context.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }

    private ProxyRequest createProxyRequest(
            final FullHttpRequest inboundRequest,
            final boolean keepAlive,
            final String clientIp,
            final String traceId) {
        final byte[] body = new byte[inboundRequest.content().readableBytes()];
        inboundRequest.content().getBytes(inboundRequest.content().readerIndex(), body);
        final HttpHeaders headers = new DefaultHttpHeaders();
        headers.set(inboundRequest.headers());

        return new ProxyRequest(
                inboundRequest.method(),
                inboundRequest.uri(),
                headers,
                body,
                keepAlive,
                clientIp,
                traceId,
                System.nanoTime());
    }

    private static void writeTraceId(final HttpHeaders headers, final String traceId) {
        if (traceId != null && !traceId.isBlank()) {
            headers.set(TRACE_ID_HEADER, traceId);
        }
    }

    private static long latencyMs(final long startTimeNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTimeNanos);
    }

    private static void logAccessSuccess(
            final String traceId,
            final String uri,
            final String method,
            final int status,
            final long latencyMs,
            final String routeId,
            final String upstream) {
        LOGGER.info(
                "traceId={} uri={} method={} status={} latencyMs={} routeId={} upstream={} errorCode={}",
                traceId,
                uri,
                method,
                status,
                latencyMs,
                routeId,
                upstream,
                "-");
    }

    private static void logAccessFailure(
            final String traceId,
            final String uri,
            final String method,
            final int status,
            final long latencyMs,
            final String routeId,
            final String upstream,
            final String errorCode) {
        LOGGER.warn(
                "traceId={} uri={} method={} status={} latencyMs={} routeId={} upstream={} errorCode={}",
                traceId,
                uri,
                method,
                status,
                latencyMs,
                routeId,
                upstream,
                errorCode);
    }

    private static String buildUpstreamAddress(final RouteConfig route) {
        return route.upstream().scheme()
                + "://"
                + route.upstream().host()
                + ":"
                + route.upstream().port();
    }

    private static String resolveClientIp(final ChannelHandlerContext context) {
        final SocketAddress remoteAddress = context.channel().remoteAddress();
        if (remoteAddress instanceof InetSocketAddress socketAddress
                && socketAddress.getAddress() != null) {
            return socketAddress.getAddress().getHostAddress();
        }
        return "unknown";
    }

    private static String resolveTraceId(final HttpHeaders headers) {
        final String existed = headers.get(TRACE_ID_HEADER);
        if (existed != null && !existed.isBlank()) {
            return existed;
        }
        return UUID.randomUUID().toString().replace("-", "");
    }

    private sealed interface RequestDispatchTarget
            permits MetricsTarget, HealthTarget, RoutedTarget, NotFoundTarget {
        String type();
    }

    private record MetricsTarget() implements RequestDispatchTarget {
        @Override
        public String type() {
            return "metrics";
        }
    }

    private record HealthTarget() implements RequestDispatchTarget {
        @Override
        public String type() {
            return "health";
        }
    }

    private record RoutedTarget(RouteConfig route) implements RequestDispatchTarget {
        private RoutedTarget {
            Objects.requireNonNull(route, "route must not be null");
        }

        @Override
        public String type() {
            return "routed";
        }
    }

    private record NotFoundTarget() implements RequestDispatchTarget {
        @Override
        public String type() {
            return "not_found";
        }
    }

    private final class UpstreamRouteClient {

        private final ChannelHandlerContext inboundContext;
        private final RouteConfig route;
        private final String upstreamAddress;
        private final Deque<PendingExchange> backlog;

        private Channel upstreamChannel;
        private PendingExchange inFlight;
        private boolean connecting;

        private UpstreamRouteClient(
                final ChannelHandlerContext inboundContext,
                final RouteConfig route,
                final String upstreamAddress) {
            this.inboundContext = inboundContext;
            this.route = route;
            this.upstreamAddress = upstreamAddress;
            this.backlog = new ArrayDeque<>();
        }

        private void submit(final ProxyRequest request) {
            if (pendingRequestCount() >= maxPendingPerRoute) {
                rejectForBacklogOverflow(request);
                return;
            }
            final FullHttpRequest outboundRequest = buildOutboundRequest(request, route);
            gatewayMetricsService.onUpstreamStart();
            backlog.addLast(new PendingExchange(request, outboundRequest, System.nanoTime()));
            drain();
        }

        private int pendingRequestCount() {
            return backlog.size() + (inFlight == null ? 0 : 1);
        }

        private void rejectForBacklogOverflow(final ProxyRequest request) {
            final ErrorResponse errorResponse =
                    new ErrorResponse(
                            OffsetDateTime.now(ZoneOffset.UTC).toString(),
                            request.traceId(),
                            "GATEWAY_ERROR",
                            ERROR_CODE_UPSTREAM_BACKLOG_OVERFLOW,
                            "upstream backlog overflow",
                            HttpResponseStatus.SERVICE_UNAVAILABLE.code());
            final byte[] responseBodyBytes =
                    errorResponse.toJson().getBytes(StandardCharsets.UTF_8);
            if (inboundContext.channel().isActive()) {
                writeErrorResponse(inboundContext, request.keepAlive(), errorResponse);
            }
            logAccessFailure(
                    request.traceId(),
                    request.uri(),
                    request.method().name(),
                    HttpResponseStatus.SERVICE_UNAVAILABLE.code(),
                    latencyMs(request.startTimeNanos()),
                    route.routeId(),
                    upstreamAddress,
                    ERROR_CODE_UPSTREAM_BACKLOG_OVERFLOW);
            gatewayMetricsService.onInboundComplete(
                    route.routeId(),
                    request.method().name(),
                    HttpResponseStatus.SERVICE_UNAVAILABLE.code(),
                    latencyMs(request.startTimeNanos()),
                    false,
                    ERROR_CODE_UPSTREAM_BACKLOG_OVERFLOW,
                    request.body().length,
                    responseBodyBytes.length);
        }

        private void drain() {
            if (!inboundContext.channel().isActive()) {
                failAllPending(new ClosedChannelException());
                closeChannel();
                return;
            }
            if (connecting || inFlight != null || backlog.isEmpty()) {
                return;
            }
            if (upstreamChannel == null || !upstreamChannel.isActive()) {
                connectUpstream();
                return;
            }

            final PendingExchange exchange = backlog.pollFirst();
            if (exchange == null) {
                return;
            }
            inFlight = exchange;
            upstreamChannel
                    .writeAndFlush(exchange.outboundRequest())
                    .addListener(
                            (ChannelFuture writeFuture) -> {
                                if (writeFuture.isSuccess()) {
                                    return;
                                }
                                final PendingExchange failed = inFlight;
                                inFlight = null;
                                if (failed != null) {
                                    writeMappedError(
                                            inboundContext,
                                            failed,
                                            route.routeId(),
                                            upstreamAddress,
                                            writeFuture.cause());
                                }
                                closeChannel();
                                drain();
                            });
        }

        private void connectUpstream() {
            final UpstreamConfig upstream = route.upstream();
            final int connectTimeoutMs = timeoutPolicy.connectTimeoutMs(route);
            final int readTimeoutMs = timeoutPolicy.readTimeoutMs(route);
            final int writeTimeoutMs = timeoutPolicy.writeTimeoutMs(route);
            connecting = true;
            final long connectStartTimeNanos = System.nanoTime();

            final Bootstrap bootstrap =
                    new Bootstrap()
                            .group(inboundContext.channel().eventLoop())
                            .channel(NioSocketChannel.class)
                            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeoutMs)
                            .option(ChannelOption.SO_KEEPALIVE, true)
                            .handler(
                                    new ChannelInitializer<SocketChannel>() {
                                        @Override
                                        protected void initChannel(final SocketChannel channel) {
                                            channel.pipeline().addLast(new HttpClientCodec());
                                            channel.pipeline()
                                                    .addLast(
                                                            new ReadTimeoutHandler(
                                                                    readTimeoutMs,
                                                                    TimeUnit.MILLISECONDS));
                                            channel.pipeline()
                                                    .addLast(
                                                            new WriteTimeoutHandler(
                                                                    writeTimeoutMs,
                                                                    TimeUnit.MILLISECONDS));
                                            channel.pipeline()
                                                    .addLast(
                                                            new HttpObjectAggregator(
                                                                    maxContentLength));
                                            channel.pipeline()
                                                    .addLast(
                                                            new ReusableUpstreamResponseHandler(
                                                                    UpstreamRouteClient.this));
                                        }
                                    });

            bootstrap
                    .connect(upstream.host(), upstream.port())
                    .addListener(
                            (ChannelFuture connectFuture) -> {
                                connecting = false;
                                if (!connectFuture.isSuccess()) {
                                    gatewayMetricsService.onUpstreamConnect(
                                            route.routeId(),
                                            latencyMs(connectStartTimeNanos),
                                            false,
                                            "UPSTREAM_CONNECT_FAILED");
                                    failAllPending(connectFuture.cause());
                                    return;
                                }
                                gatewayMetricsService.onUpstreamConnect(
                                        route.routeId(),
                                        latencyMs(connectStartTimeNanos),
                                        true,
                                        "-");
                                final Channel connectedChannel = connectFuture.channel();
                                upstreamChannel = connectedChannel;
                                connectedChannel
                                        .closeFuture()
                                        .addListener(
                                                closedFuture -> {
                                                    if (connectedChannel == upstreamChannel) {
                                                        upstreamChannel = null;
                                                    }
                                                    if (inFlight != null) {
                                                        final PendingExchange failed = inFlight;
                                                        inFlight = null;
                                                        final Throwable cause =
                                                                Objects.requireNonNullElseGet(
                                                                        closedFuture.cause(),
                                                                        ClosedChannelException
                                                                                ::new);
                                                        writeMappedError(
                                                                inboundContext,
                                                                failed,
                                                                route.routeId(),
                                                                upstreamAddress,
                                                                cause);
                                                    }
                                                    if (!backlog.isEmpty()) {
                                                        drain();
                                                    }
                                                });
                                drain();
                            });
        }

        private void onUpstreamResponse(final FullHttpResponse upstreamResponse) {
            final PendingExchange exchange = inFlight;
            inFlight = null;
            if (exchange == null) {
                return;
            }
            final ProxyRequest request = exchange.request();
            final long upstreamLatencyMs = latencyMs(exchange.upstreamStartTimeNanos());
            final FullHttpResponse responseToClient =
                    new DefaultFullHttpResponse(
                            HttpVersion.HTTP_1_1,
                            upstreamResponse.status(),
                            upstreamResponse.content().copy());
            responseToClient.headers().set(upstreamResponse.headers());
            removeHopByHopHeaders(responseToClient.headers());
            HttpUtil.setContentLength(responseToClient, responseToClient.content().readableBytes());
            writeTraceId(responseToClient.headers(), request.traceId());

            if (request.keepAlive()) {
                responseToClient
                        .headers()
                        .set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
            }

            if (!inboundContext.channel().isActive()) {
                ReferenceCountUtil.safeRelease(responseToClient);
                gatewayMetricsService.onUpstreamComplete(
                        route.routeId(),
                        HttpResponseStatus.BAD_GATEWAY.code(),
                        upstreamLatencyMs,
                        false,
                        "CLIENT_CHANNEL_INACTIVE",
                        request.body().length,
                        upstreamResponse.content().readableBytes());
                gatewayMetricsService.onInboundComplete(
                        route.routeId(),
                        request.method().name(),
                        HttpResponseStatus.BAD_GATEWAY.code(),
                        latencyMs(request.startTimeNanos()),
                        false,
                        "CLIENT_CHANNEL_INACTIVE",
                        request.body().length,
                        upstreamResponse.content().readableBytes());
                closeChannel();
                return;
            }

            final ChannelFuture writeFuture = inboundContext.writeAndFlush(responseToClient);
            if (!request.keepAlive()) {
                writeFuture.addListener(ChannelFutureListener.CLOSE);
            }
            writeFuture.addListener(
                    (ChannelFuture future) -> {
                        if (future.isSuccess()) {
                            logAccessSuccess(
                                    request.traceId(),
                                    request.uri(),
                                    request.method().name(),
                                    upstreamResponse.status().code(),
                                    latencyMs(request.startTimeNanos()),
                                    route.routeId(),
                                    upstreamAddress);
                            gatewayMetricsService.onUpstreamComplete(
                                    route.routeId(),
                                    upstreamResponse.status().code(),
                                    upstreamLatencyMs,
                                    true,
                                    "-",
                                    request.body().length,
                                    responseToClient.content().readableBytes());
                            gatewayMetricsService.onInboundComplete(
                                    route.routeId(),
                                    request.method().name(),
                                    upstreamResponse.status().code(),
                                    latencyMs(request.startTimeNanos()),
                                    true,
                                    "-",
                                    request.body().length,
                                    responseToClient.content().readableBytes());
                        } else {
                            logAccessFailure(
                                    request.traceId(),
                                    request.uri(),
                                    request.method().name(),
                                    HttpResponseStatus.BAD_GATEWAY.code(),
                                    latencyMs(request.startTimeNanos()),
                                    route.routeId(),
                                    upstreamAddress,
                                    "CLIENT_WRITE_FAILED");
                            gatewayMetricsService.onUpstreamComplete(
                                    route.routeId(),
                                    HttpResponseStatus.BAD_GATEWAY.code(),
                                    upstreamLatencyMs,
                                    false,
                                    "CLIENT_WRITE_FAILED",
                                    request.body().length,
                                    responseToClient.content().readableBytes());
                            gatewayMetricsService.onInboundComplete(
                                    route.routeId(),
                                    request.method().name(),
                                    HttpResponseStatus.BAD_GATEWAY.code(),
                                    latencyMs(request.startTimeNanos()),
                                    false,
                                    "CLIENT_WRITE_FAILED",
                                    request.body().length,
                                    responseToClient.content().readableBytes());
                        }
                    });
            drain();
        }

        private void onUpstreamException(final Throwable cause) {
            final PendingExchange exchange = inFlight;
            inFlight = null;
            if (exchange != null) {
                writeMappedError(inboundContext, exchange, route.routeId(), upstreamAddress, cause);
            }
            closeChannel();
            drain();
        }

        private void close() {
            failAllPending(new ClosedChannelException());
            if (inFlight != null) {
                writeMappedError(
                        inboundContext,
                        inFlight,
                        route.routeId(),
                        upstreamAddress,
                        new ClosedChannelException());
                inFlight = null;
            }
            closeChannel();
        }

        private void closeChannel() {
            if (upstreamChannel != null) {
                upstreamChannel.close();
                upstreamChannel = null;
            }
        }

        private static void removeHopByHopHeaders(final HttpHeaders headers) {
            for (CharSequence header : HOP_BY_HOP_HEADERS) {
                headers.remove(header);
            }
        }

        private void failAllPending(final Throwable cause) {
            PendingExchange exchange;
            while ((exchange = backlog.pollFirst()) != null) {
                ReferenceCountUtil.safeRelease(exchange.outboundRequest());
                writeMappedError(inboundContext, exchange, route.routeId(), upstreamAddress, cause);
            }
        }
    }

    private static final class ReusableUpstreamResponseHandler
            extends SimpleChannelInboundHandler<FullHttpResponse> {

        private final UpstreamRouteClient routeClient;

        private ReusableUpstreamResponseHandler(final UpstreamRouteClient routeClient) {
            this.routeClient = routeClient;
        }

        @Override
        protected void channelRead0(
                final ChannelHandlerContext upstreamContext,
                final FullHttpResponse upstreamResponse) {
            routeClient.onUpstreamResponse(upstreamResponse);
        }

        @Override
        public void exceptionCaught(final ChannelHandlerContext context, final Throwable cause) {
            routeClient.onUpstreamException(cause);
            context.close();
        }
    }

    private record ProxyRequest(
            HttpMethod method,
            String uri,
            HttpHeaders headers,
            byte[] body,
            boolean keepAlive,
            String clientIp,
            String traceId,
            long startTimeNanos) {
        private ProxyRequest {
            // no-op
        }
    }

    private record PendingExchange(
            ProxyRequest request, FullHttpRequest outboundRequest, long upstreamStartTimeNanos) {
        private PendingExchange {
            // no-op
        }
    }
}
