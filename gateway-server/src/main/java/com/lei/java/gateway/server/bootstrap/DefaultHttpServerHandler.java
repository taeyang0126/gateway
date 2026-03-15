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

import java.io.ByteArrayOutputStream;
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
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.DefaultLastHttpContent;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMessage;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import io.netty.util.ReferenceCountUtil;

final class DefaultHttpServerHandler extends SimpleChannelInboundHandler<HttpObject> {

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
    private static final String ROUTE_ID_ROUTE_NOT_FOUND = "route_not_found";
    private static final String ERROR_CODE_MANAGEMENT_ENDPOINT_FORBIDDEN =
            "MANAGEMENT_ENDPOINT_FORBIDDEN";
    private static final String ERROR_CODE_UPSTREAM_BACKLOG_OVERFLOW = "UPSTREAM_BACKLOG_OVERFLOW";
    private static final String ERROR_CODE_CLIENT_CHANNEL_INACTIVE = "CLIENT_CHANNEL_INACTIVE";
    private static final String ERROR_CODE_CLIENT_WRITE_FAILED = "CLIENT_WRITE_FAILED";
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

    private InboundRequestState currentInboundRequest;

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
    protected void channelRead0(final ChannelHandlerContext context, final HttpObject message) {
        if (message instanceof HttpRequest request) {
            handleInboundRequestStart(context, request);
        }
        if (message instanceof HttpContent content) {
            handleInboundRequestContent(context, content);
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

    private void handleInboundRequestStart(
            final ChannelHandlerContext context, final HttpRequest inboundRequest) {
        if (currentInboundRequest != null) {
            throw new IllegalStateException("previous inbound request has not completed");
        }

        gatewayMetricsService.onInboundStart();
        final boolean keepAlive = HttpUtil.isKeepAlive(inboundRequest);
        final String traceId = resolveTraceId(inboundRequest.headers());
        final RequestContext requestContext =
                createRequestContext(inboundRequest, keepAlive, resolveClientIp(context), traceId);

        final QueryStringDecoder decoder = new QueryStringDecoder(requestContext.uri());
        final String requestPath = decoder.path();
        final RequestDispatchTarget target = resolveDispatchTarget(requestPath);
        if (target instanceof RoutedTarget routedTarget) {
            final RouteConfig route = routedTarget.route();
            final String routeId = route.routeId();
            final String upstreamAddress = buildUpstreamAddress(route);
            final UpstreamRouteClient routeClient =
                    upstreamClients.computeIfAbsent(
                            routeId,
                            ignored -> new UpstreamRouteClient(context, route, upstreamAddress));
            final PendingExchange pendingExchange = routeClient.begin(requestContext);
            if (pendingExchange == null) {
                currentInboundRequest =
                        InboundRequestState.discardForBacklogOverflow(
                                requestContext, route, routeClient);
                return;
            }

            if (shouldAggregateByHeaders(inboundRequest)) {
                currentInboundRequest =
                        InboundRequestState.aggregate(
                                requestContext, route, routeClient, pendingExchange);
                return;
            }

            final HttpRequest outboundRequestHead =
                    buildStreamingOutboundRequest(requestContext, route, inboundRequest);
            routeClient.appendRequestPart(pendingExchange, outboundRequestHead, false, 0);
            currentInboundRequest =
                    InboundRequestState.stream(requestContext, route, routeClient, pendingExchange);
            return;
        }

        currentInboundRequest = InboundRequestState.discardForLocalTarget(requestContext, target);
    }

    private void handleInboundRequestContent(
            final ChannelHandlerContext context, final HttpContent inboundContent) {
        if (currentInboundRequest == null) {
            return;
        }

        final InboundRequestState state = currentInboundRequest;
        final int readableBytes = inboundContent.content().readableBytes();
        state.requestBytes += readableBytes;

        if (state.isRouted() && !state.backlogRejected()) {
            if (state.bodyMode() == RequestBodyMode.AGGREGATE) {
                appendBytes(state.requestBodyBuffer(), inboundContent.content());
                if (inboundContent instanceof LastHttpContent) {
                    final byte[] requestBody = state.requestBodyBuffer().toByteArray();
                    final DefaultFullHttpRequest outbound =
                            buildAggregatedOutboundRequest(
                                    state.request(), state.route(), requestBody);
                    state.routeClient()
                            .completeAggregatedRequest(
                                    state.pendingExchange(), outbound, state.requestBytes);
                }
            } else {
                final HttpContent outboundContent = duplicateHttpContent(inboundContent);
                final boolean completed = inboundContent instanceof LastHttpContent;
                state.routeClient()
                        .appendRequestPart(
                                state.pendingExchange(), outboundContent, completed, readableBytes);
            }
        }

        if (inboundContent instanceof LastHttpContent) {
            if (!state.isRouted() || state.backlogRejected()) {
                finalizeLocalOrRejectedRequest(context, state);
            }
            currentInboundRequest = null;
        }
    }

    private void finalizeLocalOrRejectedRequest(
            final ChannelHandlerContext context, final InboundRequestState state) {
        if (state.backlogRejected()) {
            handleBacklogOverflowRequest(context, state);
            return;
        }

        final RequestDispatchTarget target = state.target();
        if (target instanceof MetricsTarget) {
            if (!isManagementClientAllowed(state.request().clientIp())) {
                handleManagementForbiddenRequest(
                        context, state.request(), ROUTE_ID_LOCAL_METRICS, state.requestBytes);
                return;
            }
            handleMetricsRequest(context, state.request(), state.requestBytes);
            return;
        }
        if (target instanceof HealthTarget) {
            if (!isManagementClientAllowed(state.request().clientIp())) {
                handleManagementForbiddenRequest(
                        context, state.request(), ROUTE_ID_LOCAL_HEALTH, state.requestBytes);
                return;
            }
            handleHealthRequest(context, state.request(), state.requestBytes);
            return;
        }
        if (target instanceof NotFoundTarget) {
            handleNotFoundRequest(context, state.request(), state.requestBytes);
            return;
        }
        throw new IllegalStateException("unexpected request target: " + target.type());
    }

    private void handleMetricsRequest(
            final ChannelHandlerContext context,
            final RequestContext request,
            final long requestBytes) {
        final byte[] body = gatewayMetricsService.scrape().getBytes(StandardCharsets.UTF_8);
        writePlainTextResponse(
                context,
                request.keepAlive(),
                HttpResponseStatus.OK,
                body,
                request.traceId(),
                CONTENT_TYPE_PROMETHEUS);
        gatewayMetricsService.onInboundComplete(
                ROUTE_ID_LOCAL_METRICS,
                request.method().name(),
                HttpResponseStatus.OK.code(),
                latencyMs(request.startTimeNanos()),
                true,
                "-",
                requestBytes,
                body.length);
    }

    private void handleHealthRequest(
            final ChannelHandlerContext context,
            final RequestContext request,
            final long requestBytes) {
        writePlainTextResponse(
                context,
                request.keepAlive(),
                HttpResponseStatus.OK,
                HEALTH_BODY,
                request.traceId(),
                CONTENT_TYPE_TEXT);
        logAccessSuccess(
                request.traceId(),
                request.uri(),
                request.method().name(),
                HttpResponseStatus.OK.code(),
                latencyMs(request.startTimeNanos()),
                ROUTE_ID_LOCAL_HEALTH,
                "local://health");
        gatewayMetricsService.onInboundComplete(
                ROUTE_ID_LOCAL_HEALTH,
                request.method().name(),
                HttpResponseStatus.OK.code(),
                latencyMs(request.startTimeNanos()),
                true,
                "-",
                requestBytes,
                HEALTH_BODY.length);
    }

    private void handleManagementForbiddenRequest(
            final ChannelHandlerContext context,
            final RequestContext request,
            final String routeId,
            final long requestBytes) {
        final ErrorResponse errorResponse =
                new ErrorResponse(
                        OffsetDateTime.now(ZoneOffset.UTC).toString(),
                        request.traceId(),
                        "GATEWAY_ERROR",
                        ERROR_CODE_MANAGEMENT_ENDPOINT_FORBIDDEN,
                        "management endpoint forbidden",
                        HttpResponseStatus.FORBIDDEN.code());
        final byte[] responseBodyBytes = errorResponse.toJson().getBytes(StandardCharsets.UTF_8);
        writeErrorResponse(context, request.keepAlive(), errorResponse);
        logAccessFailure(
                request.traceId(),
                request.uri(),
                request.method().name(),
                HttpResponseStatus.FORBIDDEN.code(),
                latencyMs(request.startTimeNanos()),
                routeId,
                "local://management",
                ERROR_CODE_MANAGEMENT_ENDPOINT_FORBIDDEN);
        gatewayMetricsService.onInboundComplete(
                routeId,
                request.method().name(),
                HttpResponseStatus.FORBIDDEN.code(),
                latencyMs(request.startTimeNanos()),
                false,
                ERROR_CODE_MANAGEMENT_ENDPOINT_FORBIDDEN,
                requestBytes,
                responseBodyBytes.length);
    }

    private void handleNotFoundRequest(
            final ChannelHandlerContext context,
            final RequestContext request,
            final long requestBytes) {
        final ErrorResponse errorResponse =
                new ErrorResponse(
                        OffsetDateTime.now(ZoneOffset.UTC).toString(),
                        request.traceId(),
                        "GATEWAY_ERROR",
                        "ROUTE_NOT_FOUND",
                        "route not found",
                        HttpResponseStatus.NOT_FOUND.code());
        final byte[] responseBodyBytes = errorResponse.toJson().getBytes(StandardCharsets.UTF_8);
        writeErrorResponse(context, request.keepAlive(), errorResponse);
        logAccessFailure(
                request.traceId(),
                request.uri(),
                request.method().name(),
                HttpResponseStatus.NOT_FOUND.code(),
                latencyMs(request.startTimeNanos()),
                ROUTE_ID_ROUTE_NOT_FOUND,
                "-",
                "ROUTE_NOT_FOUND");
        gatewayMetricsService.onInboundComplete(
                ROUTE_ID_ROUTE_NOT_FOUND,
                request.method().name(),
                HttpResponseStatus.NOT_FOUND.code(),
                latencyMs(request.startTimeNanos()),
                false,
                "ROUTE_NOT_FOUND",
                requestBytes,
                responseBodyBytes.length);
    }

    private void handleBacklogOverflowRequest(
            final ChannelHandlerContext context, final InboundRequestState state) {
        final RequestContext request = state.request();
        final RouteConfig route = state.route();
        final ErrorResponse errorResponse =
                new ErrorResponse(
                        OffsetDateTime.now(ZoneOffset.UTC).toString(),
                        request.traceId(),
                        "GATEWAY_ERROR",
                        ERROR_CODE_UPSTREAM_BACKLOG_OVERFLOW,
                        "upstream backlog overflow",
                        HttpResponseStatus.SERVICE_UNAVAILABLE.code());
        final byte[] responseBodyBytes = errorResponse.toJson().getBytes(StandardCharsets.UTF_8);
        if (context.channel().isActive()) {
            writeErrorResponse(context, request.keepAlive(), errorResponse);
        }
        logAccessFailure(
                request.traceId(),
                request.uri(),
                request.method().name(),
                HttpResponseStatus.SERVICE_UNAVAILABLE.code(),
                latencyMs(request.startTimeNanos()),
                route.routeId(),
                buildUpstreamAddress(route),
                ERROR_CODE_UPSTREAM_BACKLOG_OVERFLOW);
        gatewayMetricsService.onInboundComplete(
                route.routeId(),
                request.method().name(),
                HttpResponseStatus.SERVICE_UNAVAILABLE.code(),
                latencyMs(request.startTimeNanos()),
                false,
                ERROR_CODE_UPSTREAM_BACKLOG_OVERFLOW,
                state.requestBytes(),
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

    private DefaultFullHttpRequest buildAggregatedOutboundRequest(
            final RequestContext request, final RouteConfig route, final byte[] bodyBytes) {
        final ByteBuf body = Unpooled.wrappedBuffer(bodyBytes);
        final DefaultFullHttpRequest outboundRequest =
                new DefaultFullHttpRequest(
                        HttpVersion.HTTP_1_1, request.method(), request.uri(), body);

        headerPolicyService.applyRequestHeaders(
                request.headers(),
                outboundRequest.headers(),
                route,
                request.clientIp(),
                request.traceId());
        HttpUtil.setContentLength(outboundRequest, body.readableBytes());
        return outboundRequest;
    }

    private HttpRequest buildStreamingOutboundRequest(
            final RequestContext request,
            final RouteConfig route,
            final HttpRequest inboundRequestHead) {
        final DefaultHttpRequest outboundRequest =
                new DefaultHttpRequest(HttpVersion.HTTP_1_1, request.method(), request.uri());
        headerPolicyService.applyRequestHeaders(
                request.headers(),
                outboundRequest.headers(),
                route,
                request.clientIp(),
                request.traceId());

        if (HttpUtil.isTransferEncodingChunked(inboundRequestHead)) {
            HttpUtil.setTransferEncodingChunked(outboundRequest, true);
            outboundRequest.headers().remove(HttpHeaderNames.CONTENT_LENGTH);
            return outboundRequest;
        }

        final long contentLength = HttpUtil.getContentLength(inboundRequestHead, -1);
        if (contentLength >= 0) {
            outboundRequest.headers().set(HttpHeaderNames.CONTENT_LENGTH, contentLength);
            outboundRequest.headers().remove(HttpHeaderNames.TRANSFER_ENCODING);
            return outboundRequest;
        }

        HttpUtil.setTransferEncodingChunked(outboundRequest, true);
        outboundRequest.headers().remove(HttpHeaderNames.CONTENT_LENGTH);
        return outboundRequest;
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

    private RequestContext createRequestContext(
            final HttpRequest inboundRequest,
            final boolean keepAlive,
            final String clientIp,
            final String traceId) {
        final HttpHeaders headers = new DefaultHttpHeaders();
        headers.set(inboundRequest.headers());
        return new RequestContext(
                inboundRequest.method(),
                inboundRequest.uri(),
                headers,
                keepAlive,
                clientIp,
                traceId,
                System.nanoTime());
    }

    private boolean shouldAggregateByHeaders(final HttpMessage message) {
        if (HttpUtil.isTransferEncodingChunked(message)) {
            return false;
        }
        final long contentLength = HttpUtil.getContentLength(message, -1L);
        return contentLength >= 0L && contentLength <= maxContentLength;
    }

    private static HttpContent duplicateHttpContent(final HttpContent content) {
        final ByteBuf copiedContent = Unpooled.copiedBuffer(content.content());
        if (content instanceof LastHttpContent lastContent) {
            final DefaultLastHttpContent duplicated = new DefaultLastHttpContent(copiedContent);
            duplicated.trailingHeaders().set(lastContent.trailingHeaders());
            return duplicated;
        }
        return new DefaultHttpContent(copiedContent);
    }

    private static void appendBytes(final ByteArrayOutputStream out, final ByteBuf source) {
        final int readable = source.readableBytes();
        if (readable <= 0) {
            return;
        }
        final byte[] bytes = new byte[readable];
        source.getBytes(source.readerIndex(), bytes);
        out.writeBytes(bytes);
    }

    private static ByteArrayOutputStream createBodyBuffer(final HttpMessage message) {
        final long contentLength = HttpUtil.getContentLength(message, -1L);
        if (contentLength > 0L && contentLength <= Integer.MAX_VALUE) {
            return new ByteArrayOutputStream((int) contentLength);
        }
        return new ByteArrayOutputStream(256);
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

    private enum RequestBodyMode {
        AGGREGATE,
        STREAM,
        DISCARD
    }

    private enum ResponseBodyMode {
        AGGREGATE,
        STREAM
    }

    private final class UpstreamRouteClient {

        private final ChannelHandlerContext inboundContext;
        private final RouteConfig route;
        private final String upstreamAddress;
        private final Deque<PendingExchange> backlog;

        private Channel upstreamChannel;
        private PendingExchange inFlight;
        private boolean connecting;
        private boolean writingRequestPart;

        private UpstreamRouteClient(
                final ChannelHandlerContext inboundContext,
                final RouteConfig route,
                final String upstreamAddress) {
            this.inboundContext = inboundContext;
            this.route = route;
            this.upstreamAddress = upstreamAddress;
            this.backlog = new ArrayDeque<>();
        }

        private PendingExchange begin(final RequestContext request) {
            if (pendingRequestCount() >= maxPendingPerRoute) {
                return null;
            }
            final PendingExchange exchange = new PendingExchange(request, System.nanoTime());
            backlog.addLast(exchange);
            gatewayMetricsService.onUpstreamStart();
            drain();
            return exchange;
        }

        private int pendingRequestCount() {
            return backlog.size() + (inFlight == null ? 0 : 1);
        }

        private void appendRequestPart(
                final PendingExchange exchange,
                final HttpObject outboundPart,
                final boolean completed,
                final long bodyBytes) {
            exchange.enqueueRequestPart(outboundPart, bodyBytes);
            drain();
        }

        private void completeAggregatedRequest(
                final PendingExchange exchange,
                final DefaultFullHttpRequest outboundRequest,
                final long requestBytes) {
            exchange.setRequestBytes(requestBytes);
            exchange.enqueueRequestPart(outboundRequest, 0);
            drain();
        }

        private void drain() {
            if (!inboundContext.channel().isActive()) {
                failAllPending(new ClosedChannelException());
                closeChannel();
                return;
            }
            if (connecting) {
                return;
            }
            if (inFlight == null) {
                if (backlog.isEmpty()) {
                    return;
                }
                if (upstreamChannel == null || !upstreamChannel.isActive()) {
                    connectUpstream();
                    return;
                }
                inFlight = backlog.pollFirst();
            }
            if (upstreamChannel == null || !upstreamChannel.isActive()) {
                connectUpstream();
                return;
            }
            writeNextRequestPart();
        }

        private void writeNextRequestPart() {
            if (writingRequestPart
                    || inFlight == null
                    || upstreamChannel == null
                    || !upstreamChannel.isActive()) {
                return;
            }

            final HttpObject part = inFlight.pollRequestPart();
            if (part == null) {
                return;
            }

            writingRequestPart = true;
            upstreamChannel
                    .writeAndFlush(part)
                    .addListener(
                            (ChannelFuture writeFuture) -> {
                                writingRequestPart = false;
                                if (!writeFuture.isSuccess()) {
                                    final PendingExchange failed = inFlight;
                                    inFlight = null;
                                    if (failed != null) {
                                        releaseQueuedRequestParts(failed);
                                        emitMappedError(failed, writeFuture.cause());
                                    }
                                    closeChannel();
                                    drain();
                                    return;
                                }
                                writeNextRequestPart();
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
                                                        releaseQueuedRequestParts(failed);
                                                        emitMappedError(failed, cause);
                                                    }
                                                    if (!backlog.isEmpty()) {
                                                        drain();
                                                    }
                                                });
                                drain();
                            });
        }

        private void onUpstreamObject(final HttpObject upstreamObject) {
            final PendingExchange exchange = inFlight;
            if (exchange == null) {
                return;
            }

            if (upstreamObject instanceof HttpResponse response) {
                onUpstreamResponseHead(exchange, response);
            }
            if (upstreamObject instanceof HttpContent content) {
                onUpstreamResponseContent(exchange, content);
            }
        }

        private void onUpstreamResponseHead(
                final PendingExchange exchange, final HttpResponse upstreamResponseHead) {
            if (exchange.completed()) {
                return;
            }

            exchange.setUpstreamStatus(upstreamResponseHead.status().code());
            if (shouldAggregateByHeaders(upstreamResponseHead)) {
                exchange.setResponseMode(ResponseBodyMode.AGGREGATE);
                final HttpHeaders copiedHeaders = new DefaultHttpHeaders();
                copiedHeaders.set(upstreamResponseHead.headers());
                exchange.setUpstreamResponseHeaders(copiedHeaders);
                exchange.setResponseBodyBuffer(createBodyBuffer(upstreamResponseHead));
                return;
            }

            exchange.setResponseMode(ResponseBodyMode.STREAM);
            final DefaultHttpResponse responseHeadToClient =
                    new DefaultHttpResponse(HttpVersion.HTTP_1_1, upstreamResponseHead.status());
            responseHeadToClient.headers().set(upstreamResponseHead.headers());
            removeHopByHopHeaders(responseHeadToClient.headers());
            writeTraceId(responseHeadToClient.headers(), exchange.request().traceId());
            if (exchange.request().keepAlive()) {
                responseHeadToClient
                        .headers()
                        .set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
            }
            if (!HttpUtil.isTransferEncodingChunked(responseHeadToClient)
                    && !HttpUtil.isContentLengthSet(responseHeadToClient)) {
                HttpUtil.setTransferEncodingChunked(responseHeadToClient, true);
            }

            if (!inboundContext.channel().isActive()) {
                completeClientWriteFailure(exchange, ERROR_CODE_CLIENT_CHANNEL_INACTIVE);
                return;
            }

            inboundContext
                    .writeAndFlush(responseHeadToClient)
                    .addListener(
                            (ChannelFuture writeFuture) -> {
                                if (!writeFuture.isSuccess()) {
                                    completeClientWriteFailure(
                                            exchange, ERROR_CODE_CLIENT_WRITE_FAILED);
                                }
                            });
        }

        private void onUpstreamResponseContent(
                final PendingExchange exchange, final HttpContent upstreamContent) {
            if (exchange.completed()) {
                return;
            }

            exchange.addResponseBytes(upstreamContent.content().readableBytes());
            if (exchange.responseMode() == ResponseBodyMode.AGGREGATE) {
                appendBytes(exchange.responseBodyBuffer(), upstreamContent.content());
                if (upstreamContent instanceof LastHttpContent) {
                    writeAggregatedResponseToInbound(exchange);
                }
                return;
            }

            final HttpContent contentToClient = duplicateHttpContent(upstreamContent);
            if (!inboundContext.channel().isActive()) {
                ReferenceCountUtil.safeRelease(contentToClient);
                completeClientWriteFailure(exchange, ERROR_CODE_CLIENT_CHANNEL_INACTIVE);
                return;
            }

            final boolean last = upstreamContent instanceof LastHttpContent;
            final ChannelFuture writeFuture = inboundContext.writeAndFlush(contentToClient);
            if (last && !exchange.request().keepAlive()) {
                writeFuture.addListener(ChannelFutureListener.CLOSE);
            }
            writeFuture.addListener(
                    (ChannelFuture future) -> {
                        if (!future.isSuccess()) {
                            completeClientWriteFailure(exchange, ERROR_CODE_CLIENT_WRITE_FAILED);
                            return;
                        }
                        if (last) {
                            completeSuccess(exchange);
                        }
                    });
        }

        private void writeAggregatedResponseToInbound(final PendingExchange exchange) {
            if (exchange.completed()) {
                return;
            }

            final byte[] responseBodyBytes = exchange.responseBodyBuffer().toByteArray();
            final FullHttpResponse responseToClient =
                    new DefaultFullHttpResponse(
                            HttpVersion.HTTP_1_1,
                            HttpResponseStatus.valueOf(exchange.upstreamStatus()),
                            Unpooled.wrappedBuffer(responseBodyBytes));
            responseToClient.headers().set(exchange.upstreamResponseHeaders());
            removeHopByHopHeaders(responseToClient.headers());
            HttpUtil.setContentLength(responseToClient, responseToClient.content().readableBytes());
            writeTraceId(responseToClient.headers(), exchange.request().traceId());

            if (exchange.request().keepAlive()) {
                responseToClient
                        .headers()
                        .set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
            }

            if (!inboundContext.channel().isActive()) {
                ReferenceCountUtil.safeRelease(responseToClient);
                completeClientWriteFailure(exchange, ERROR_CODE_CLIENT_CHANNEL_INACTIVE);
                return;
            }

            final ChannelFuture writeFuture = inboundContext.writeAndFlush(responseToClient);
            if (!exchange.request().keepAlive()) {
                writeFuture.addListener(ChannelFutureListener.CLOSE);
            }
            writeFuture.addListener(
                    (ChannelFuture future) -> {
                        if (!future.isSuccess()) {
                            completeClientWriteFailure(exchange, ERROR_CODE_CLIENT_WRITE_FAILED);
                            return;
                        }
                        completeSuccess(exchange);
                    });
        }

        private void completeSuccess(final PendingExchange exchange) {
            if (exchange.completed()) {
                return;
            }
            exchange.markCompleted();

            logAccessSuccess(
                    exchange.request().traceId(),
                    exchange.request().uri(),
                    exchange.request().method().name(),
                    exchange.upstreamStatus(),
                    latencyMs(exchange.request().startTimeNanos()),
                    route.routeId(),
                    upstreamAddress);

            gatewayMetricsService.onUpstreamComplete(
                    route.routeId(),
                    exchange.upstreamStatus(),
                    latencyMs(exchange.upstreamStartTimeNanos()),
                    true,
                    "-",
                    exchange.requestBytes(),
                    exchange.responseBytes());
            gatewayMetricsService.onInboundComplete(
                    route.routeId(),
                    exchange.request().method().name(),
                    exchange.upstreamStatus(),
                    latencyMs(exchange.request().startTimeNanos()),
                    true,
                    "-",
                    exchange.requestBytes(),
                    exchange.responseBytes());

            if (exchange == inFlight) {
                inFlight = null;
            }
            drain();
        }

        private void completeClientWriteFailure(
                final PendingExchange exchange, final String errorCode) {
            if (exchange.completed()) {
                return;
            }
            exchange.markCompleted();

            logAccessFailure(
                    exchange.request().traceId(),
                    exchange.request().uri(),
                    exchange.request().method().name(),
                    HttpResponseStatus.BAD_GATEWAY.code(),
                    latencyMs(exchange.request().startTimeNanos()),
                    route.routeId(),
                    upstreamAddress,
                    errorCode);
            gatewayMetricsService.onUpstreamComplete(
                    route.routeId(),
                    HttpResponseStatus.BAD_GATEWAY.code(),
                    latencyMs(exchange.upstreamStartTimeNanos()),
                    false,
                    errorCode,
                    exchange.requestBytes(),
                    exchange.responseBytes());
            gatewayMetricsService.onInboundComplete(
                    route.routeId(),
                    exchange.request().method().name(),
                    HttpResponseStatus.BAD_GATEWAY.code(),
                    latencyMs(exchange.request().startTimeNanos()),
                    false,
                    errorCode,
                    exchange.requestBytes(),
                    exchange.responseBytes());

            if (exchange == inFlight) {
                inFlight = null;
            }
            closeChannel();
            drain();
        }

        private void emitMappedError(final PendingExchange exchange, final Throwable throwable) {
            if (exchange.completed()) {
                return;
            }
            exchange.markCompleted();

            final ErrorResponse errorResponse =
                    errorResponseMapper.map(
                            Objects.requireNonNullElseGet(
                                    throwable,
                                    () -> new IllegalStateException("unknown upstream error")),
                            exchange.request().traceId());
            final byte[] responseBodyBytes =
                    errorResponse.toJson().getBytes(StandardCharsets.UTF_8);
            if (inboundContext.channel().isActive()) {
                writeErrorResponse(inboundContext, exchange.request().keepAlive(), errorResponse);
            }
            logAccessFailure(
                    exchange.request().traceId(),
                    exchange.request().uri(),
                    exchange.request().method().name(),
                    errorResponse.status(),
                    latencyMs(exchange.request().startTimeNanos()),
                    route.routeId(),
                    upstreamAddress,
                    errorResponse.code());
            gatewayMetricsService.onUpstreamComplete(
                    route.routeId(),
                    errorResponse.status(),
                    latencyMs(exchange.upstreamStartTimeNanos()),
                    false,
                    errorResponse.code(),
                    exchange.requestBytes(),
                    responseBodyBytes.length);
            gatewayMetricsService.onInboundComplete(
                    route.routeId(),
                    exchange.request().method().name(),
                    errorResponse.status(),
                    latencyMs(exchange.request().startTimeNanos()),
                    false,
                    errorResponse.code(),
                    exchange.requestBytes(),
                    responseBodyBytes.length);
        }

        private void close() {
            failAllPending(new ClosedChannelException());
            if (inFlight != null) {
                final PendingExchange failed = inFlight;
                inFlight = null;
                releaseQueuedRequestParts(failed);
                emitMappedError(failed, new ClosedChannelException());
            }
            closeChannel();
        }

        private void closeChannel() {
            if (upstreamChannel != null) {
                upstreamChannel.close();
                upstreamChannel = null;
            }
        }

        private void failAllPending(final Throwable cause) {
            PendingExchange exchange;
            while ((exchange = backlog.pollFirst()) != null) {
                releaseQueuedRequestParts(exchange);
                emitMappedError(exchange, cause);
            }
        }

        private void onUpstreamException(final Throwable cause) {
            if (inFlight != null) {
                final PendingExchange failed = inFlight;
                inFlight = null;
                releaseQueuedRequestParts(failed);
                emitMappedError(failed, cause);
            }
            closeChannel();
            failAllPending(cause);
            drain();
        }

        private void releaseQueuedRequestParts(final PendingExchange exchange) {
            HttpObject pending;
            while ((pending = exchange.pollRequestPart()) != null) {
                ReferenceCountUtil.safeRelease(pending);
            }
        }

        private void removeHopByHopHeaders(final HttpHeaders headers) {
            for (CharSequence header : HOP_BY_HOP_HEADERS) {
                headers.remove(header);
            }
        }
    }

    private static final class ReusableUpstreamResponseHandler
            extends SimpleChannelInboundHandler<HttpObject> {

        private final UpstreamRouteClient routeClient;

        private ReusableUpstreamResponseHandler(final UpstreamRouteClient routeClient) {
            this.routeClient = routeClient;
        }

        @Override
        protected void channelRead0(
                final ChannelHandlerContext upstreamContext, final HttpObject upstreamObject) {
            routeClient.onUpstreamObject(upstreamObject);
        }

        @Override
        public void exceptionCaught(final ChannelHandlerContext context, final Throwable cause) {
            routeClient.onUpstreamException(cause);
            context.close();
        }
    }

    private record RequestContext(
            HttpMethod method,
            String uri,
            HttpHeaders headers,
            boolean keepAlive,
            String clientIp,
            String traceId,
            long startTimeNanos) {
        private RequestContext {
            // no-op
        }
    }

    private static final class InboundRequestState {

        private final RequestContext request;
        private final RequestDispatchTarget target;
        private final RouteConfig route;
        private final UpstreamRouteClient routeClient;
        private final PendingExchange pendingExchange;
        private final RequestBodyMode bodyMode;
        private final ByteArrayOutputStream requestBodyBuffer;
        private final boolean backlogRejected;

        private long requestBytes;

        private InboundRequestState(
                final RequestContext request,
                final RequestDispatchTarget target,
                final RouteConfig route,
                final UpstreamRouteClient routeClient,
                final PendingExchange pendingExchange,
                final RequestBodyMode bodyMode,
                final ByteArrayOutputStream requestBodyBuffer,
                final boolean backlogRejected) {
            this.request = request;
            this.target = target;
            this.route = route;
            this.routeClient = routeClient;
            this.pendingExchange = pendingExchange;
            this.bodyMode = bodyMode;
            this.requestBodyBuffer = requestBodyBuffer;
            this.backlogRejected = backlogRejected;
            this.requestBytes = 0;
        }

        private static InboundRequestState aggregate(
                final RequestContext request,
                final RouteConfig route,
                final UpstreamRouteClient routeClient,
                final PendingExchange pendingExchange) {
            return new InboundRequestState(
                    request,
                    new RoutedTarget(route),
                    route,
                    routeClient,
                    pendingExchange,
                    RequestBodyMode.AGGREGATE,
                    createBodyBufferForAggregateRequest(request),
                    false);
        }

        private static InboundRequestState stream(
                final RequestContext request,
                final RouteConfig route,
                final UpstreamRouteClient routeClient,
                final PendingExchange pendingExchange) {
            return new InboundRequestState(
                    request,
                    new RoutedTarget(route),
                    route,
                    routeClient,
                    pendingExchange,
                    RequestBodyMode.STREAM,
                    null,
                    false);
        }

        private static InboundRequestState discardForLocalTarget(
                final RequestContext request, final RequestDispatchTarget target) {
            return new InboundRequestState(
                    request, target, null, null, null, RequestBodyMode.DISCARD, null, false);
        }

        private static InboundRequestState discardForBacklogOverflow(
                final RequestContext request,
                final RouteConfig route,
                final UpstreamRouteClient routeClient) {
            return new InboundRequestState(
                    request,
                    new RoutedTarget(route),
                    route,
                    routeClient,
                    null,
                    RequestBodyMode.DISCARD,
                    null,
                    true);
        }

        private static ByteArrayOutputStream createBodyBufferForAggregateRequest(
                final RequestContext request) {
            final long contentLength = parseContentLength(request.headers());
            if (contentLength > 0L && contentLength <= Integer.MAX_VALUE) {
                return new ByteArrayOutputStream((int) contentLength);
            }
            return new ByteArrayOutputStream(256);
        }

        private static long parseContentLength(final HttpHeaders headers) {
            final String value = headers.get(HttpHeaderNames.CONTENT_LENGTH);
            if (value == null || value.isBlank()) {
                return -1L;
            }
            try {
                return Long.parseLong(value.trim());
            } catch (NumberFormatException ignored) {
                return -1L;
            }
        }

        private boolean isRouted() {
            return route != null;
        }

        private RequestContext request() {
            return request;
        }

        private RequestDispatchTarget target() {
            return target;
        }

        private RouteConfig route() {
            return route;
        }

        private UpstreamRouteClient routeClient() {
            return routeClient;
        }

        private PendingExchange pendingExchange() {
            return pendingExchange;
        }

        private RequestBodyMode bodyMode() {
            return bodyMode;
        }

        private ByteArrayOutputStream requestBodyBuffer() {
            return requestBodyBuffer;
        }

        private boolean backlogRejected() {
            return backlogRejected;
        }

        private long requestBytes() {
            return requestBytes;
        }
    }

    private static final class PendingExchange {

        private final RequestContext request;
        private final Deque<HttpObject> requestParts;
        private final long upstreamStartTimeNanos;

        private boolean completed;
        private long requestBytes;
        private long responseBytes;
        private int upstreamStatus;
        private ResponseBodyMode responseMode;
        private HttpHeaders upstreamResponseHeaders;
        private ByteArrayOutputStream responseBodyBuffer;

        private PendingExchange(final RequestContext request, final long upstreamStartTimeNanos) {
            this.request = request;
            this.requestParts = new ArrayDeque<>();
            this.upstreamStartTimeNanos = upstreamStartTimeNanos;
            this.completed = false;
            this.requestBytes = 0;
            this.responseBytes = 0;
            this.upstreamStatus = HttpResponseStatus.BAD_GATEWAY.code();
            this.responseMode = null;
            this.upstreamResponseHeaders = null;
            this.responseBodyBuffer = null;
        }

        private RequestContext request() {
            return request;
        }

        private long upstreamStartTimeNanos() {
            return upstreamStartTimeNanos;
        }

        private void enqueueRequestPart(final HttpObject requestPart, final long bodyBytes) {
            requestParts.addLast(requestPart);
            requestBytes += Math.max(bodyBytes, 0);
        }

        private HttpObject pollRequestPart() {
            return requestParts.pollFirst();
        }

        private void markCompleted() {
            this.completed = true;
        }

        private boolean completed() {
            return completed;
        }

        private void setRequestBytes(final long requestBytes) {
            this.requestBytes = Math.max(requestBytes, 0);
        }

        private long requestBytes() {
            return requestBytes;
        }

        private void addResponseBytes(final long responseBytes) {
            this.responseBytes += Math.max(responseBytes, 0);
        }

        private long responseBytes() {
            return responseBytes;
        }

        private void setUpstreamStatus(final int upstreamStatus) {
            this.upstreamStatus = upstreamStatus;
        }

        private int upstreamStatus() {
            return upstreamStatus;
        }

        private void setResponseMode(final ResponseBodyMode responseMode) {
            this.responseMode = responseMode;
        }

        private ResponseBodyMode responseMode() {
            return responseMode;
        }

        private void setUpstreamResponseHeaders(final HttpHeaders upstreamResponseHeaders) {
            this.upstreamResponseHeaders = upstreamResponseHeaders;
        }

        private HttpHeaders upstreamResponseHeaders() {
            return upstreamResponseHeaders;
        }

        private void setResponseBodyBuffer(final ByteArrayOutputStream responseBodyBuffer) {
            this.responseBodyBuffer = responseBodyBuffer;
        }

        private ByteArrayOutputStream responseBodyBuffer() {
            return responseBodyBuffer;
        }
    }
}
