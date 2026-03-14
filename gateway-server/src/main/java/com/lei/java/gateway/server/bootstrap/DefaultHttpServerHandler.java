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
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import com.lei.java.gateway.server.config.RouteConfig;
import com.lei.java.gateway.server.config.UpstreamConfig;
import com.lei.java.gateway.server.http.DefaultErrorResponseMapper;
import com.lei.java.gateway.server.http.DefaultHeaderPolicyService;
import com.lei.java.gateway.server.http.ErrorResponse;
import com.lei.java.gateway.server.http.ErrorResponseMapper;
import com.lei.java.gateway.server.http.HeaderPolicyService;
import com.lei.java.gateway.server.logging.AccessLogService;
import com.lei.java.gateway.server.logging.DefaultAccessLogService;
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

    private static final CharSequence TRACE_ID_HEADER = "X-Trace-Id";
    private static final String HEALTH_PATH = "/health";
    private static final String CONTENT_TYPE_TEXT = "text/plain; charset=UTF-8";
    private static final String CONTENT_TYPE_JSON = "application/json; charset=UTF-8";
    private static final byte[] HEALTH_BODY = "OK".getBytes(StandardCharsets.UTF_8);
    private static final byte[] NOT_FOUND_BODY = "Not Found".getBytes(StandardCharsets.UTF_8);

    private final int maxContentLength;
    private final List<RouteConfig> routes;
    private final RouteService routeService;
    private final HeaderPolicyService headerPolicyService;
    private final TimeoutPolicy timeoutPolicy;
    private final ErrorResponseMapper errorResponseMapper;
    private final AccessLogService accessLogService;
    private final Map<String, UpstreamRouteClient> upstreamClients;

    DefaultHttpServerHandler(final int maxContentLength, final List<RouteConfig> routes) {
        this(
                maxContentLength,
                routes,
                new StaticRouteService(),
                new DefaultHeaderPolicyService(),
                new DefaultTimeoutPolicy(),
                new DefaultErrorResponseMapper(),
                new DefaultAccessLogService());
    }

    DefaultHttpServerHandler(
            final int maxContentLength,
            final List<RouteConfig> routes,
            final RouteService routeService,
            final HeaderPolicyService headerPolicyService,
            final TimeoutPolicy timeoutPolicy,
            final ErrorResponseMapper errorResponseMapper,
            final AccessLogService accessLogService) {
        this.maxContentLength = maxContentLength;
        this.routes = List.copyOf(Objects.requireNonNull(routes, "routes must not be null"));
        this.routeService = Objects.requireNonNull(routeService, "routeService must not be null");
        this.headerPolicyService =
                Objects.requireNonNull(headerPolicyService, "headerPolicyService must not be null");
        this.timeoutPolicy =
                Objects.requireNonNull(timeoutPolicy, "timeoutPolicy must not be null");
        this.errorResponseMapper =
                Objects.requireNonNull(errorResponseMapper, "errorResponseMapper must not be null");
        this.accessLogService =
                Objects.requireNonNull(accessLogService, "accessLogService must not be null");
        this.upstreamClients = new HashMap<>();
    }

    @Override
    protected void channelRead0(
            final ChannelHandlerContext context, final FullHttpRequest request) {
        final boolean keepAlive = HttpUtil.isKeepAlive(request);
        final String traceId = resolveTraceId(request.headers());
        final ProxyRequest proxyRequest =
                createProxyRequest(request, keepAlive, resolveClientIp(context), traceId);

        final QueryStringDecoder decoder = new QueryStringDecoder(request.uri());
        final String requestPath = decoder.path();
        if (HEALTH_PATH.equals(requestPath)) {
            writePlainTextResponse(context, keepAlive, HttpResponseStatus.OK, HEALTH_BODY, traceId);
            accessLogService.logSuccess(
                    traceId,
                    proxyRequest.uri(),
                    HttpResponseStatus.OK.code(),
                    latencyMs(proxyRequest.startTimeNanos()),
                    "local://health");
            return;
        }

        final Optional<RouteConfig> route = routeService.select(requestPath, routes);
        if (route.isEmpty()) {
            writePlainTextResponse(
                    context, keepAlive, HttpResponseStatus.NOT_FOUND, NOT_FOUND_BODY, traceId);
            accessLogService.logFailure(
                    traceId,
                    proxyRequest.uri(),
                    HttpResponseStatus.NOT_FOUND.code(),
                    "ROUTE_NOT_FOUND",
                    latencyMs(proxyRequest.startTimeNanos()),
                    "-");
            return;
        }

        forwardRequest(context, proxyRequest, route.get());
    }

    @Override
    public void exceptionCaught(final ChannelHandlerContext context, final Throwable cause) {
        final String traceId = UUID.randomUUID().toString().replace("-", "");
        final ErrorResponse errorResponse = errorResponseMapper.map(cause, traceId);
        writeErrorResponse(context, false, errorResponse);
        accessLogService.logFailure(
                traceId, "-", errorResponse.status(), errorResponse.code(), 0, "-");
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
            final ProxyRequest request,
            final String upstreamAddress,
            final Throwable throwable) {
        final ErrorResponse errorResponse =
                errorResponseMapper.map(
                        Objects.requireNonNullElseGet(
                                throwable,
                                () -> new IllegalStateException("unknown upstream error")),
                        request.traceId());
        if (context.channel().isActive()) {
            writeErrorResponse(context, request.keepAlive(), errorResponse);
        }
        accessLogService.logFailure(
                request.traceId(),
                request.uri(),
                errorResponse.status(),
                errorResponse.code(),
                latencyMs(request.startTimeNanos()),
                upstreamAddress);
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
            final String traceId) {
        final FullHttpResponse response =
                new DefaultFullHttpResponse(
                        HttpVersion.HTTP_1_1, status, Unpooled.wrappedBuffer(body));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, CONTENT_TYPE_TEXT);
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
            final FullHttpRequest outboundRequest = buildOutboundRequest(request, route);
            backlog.addLast(new PendingExchange(request, outboundRequest));
            drain();
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
                                            failed.request(),
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
                                    failAllPending(connectFuture.cause());
                                    return;
                                }
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
                                                                failed.request(),
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
            final FullHttpResponse responseToClient =
                    new DefaultFullHttpResponse(
                            HttpVersion.HTTP_1_1,
                            upstreamResponse.status(),
                            upstreamResponse.content().copy());
            responseToClient.headers().set(upstreamResponse.headers());
            responseToClient.headers().remove(HttpHeaderNames.TRANSFER_ENCODING);
            HttpUtil.setContentLength(responseToClient, responseToClient.content().readableBytes());
            writeTraceId(responseToClient.headers(), request.traceId());

            if (request.keepAlive()) {
                responseToClient
                        .headers()
                        .set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
            }

            if (!inboundContext.channel().isActive()) {
                ReferenceCountUtil.safeRelease(responseToClient);
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
                            accessLogService.logSuccess(
                                    request.traceId(),
                                    request.uri(),
                                    upstreamResponse.status().code(),
                                    latencyMs(request.startTimeNanos()),
                                    upstreamAddress);
                        } else {
                            accessLogService.logFailure(
                                    request.traceId(),
                                    request.uri(),
                                    HttpResponseStatus.BAD_GATEWAY.code(),
                                    "CLIENT_WRITE_FAILED",
                                    latencyMs(request.startTimeNanos()),
                                    upstreamAddress);
                        }
                    });
            drain();
        }

        private void onUpstreamException(final Throwable cause) {
            final PendingExchange exchange = inFlight;
            inFlight = null;
            if (exchange != null) {
                writeMappedError(inboundContext, exchange.request(), upstreamAddress, cause);
            }
            closeChannel();
            drain();
        }

        private void close() {
            failAllPending(new ClosedChannelException());
            if (inFlight != null) {
                writeMappedError(
                        inboundContext,
                        inFlight.request(),
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

        private void failAllPending(final Throwable cause) {
            PendingExchange exchange;
            while ((exchange = backlog.pollFirst()) != null) {
                ReferenceCountUtil.safeRelease(exchange.outboundRequest());
                writeMappedError(inboundContext, exchange.request(), upstreamAddress, cause);
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

    private record PendingExchange(ProxyRequest request, FullHttpRequest outboundRequest) {
        private PendingExchange {
            // no-op
        }
    }
}
