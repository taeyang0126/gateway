package com.lei.gateway.integration;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.Http2ConnectionHandler;
import io.netty.handler.codec.http2.Http2ConnectionEncoder;
import io.netty.handler.codec.http2.Http2DataFrame;
import io.netty.handler.codec.http2.Http2FrameCodecBuilder;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2HeadersFrame;
import io.netty.handler.codec.http2.Http2Settings;
import io.netty.util.CharsetUtil;
import java.net.InetSocketAddress;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * 轻量级 mock upstream h2c 服务，基于 Netty 实现。
 *
 * <p>支持 HTTP/2 Prior Knowledge 模式（h2c 明文）。
 * 默认行为：echo 请求体，返回 200。可通过 {@link #setHandler} 自定义。
 */
class MockUpstreamServer {

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;
    private volatile Function<FullHttpRequest, FullHttpResponse> handler;
    private final CopyOnWriteArrayList<ReceivedRequest> receivedRequests =
            new CopyOnWriteArrayList<>();
    private volatile int maxConcurrentStreams = Integer.MAX_VALUE;

    MockUpstreamServer() {
        this.handler = this::defaultHandler;
    }

    /** 启动 mock h2c 服务，绑定随机端口。 */
    void start() throws InterruptedException {
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup(1);
        int configuredMaxStreams = maxConcurrentStreams;
        ServerBootstrap bootstrap = new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        Http2Settings settings = Http2Settings.defaultSettings();
                        if (configuredMaxStreams != Integer.MAX_VALUE) {
                            settings.maxConcurrentStreams(configuredMaxStreams);
                        }
                        ch.pipeline().addLast(
                                Http2FrameCodecBuilder.forServer()
                                        .initialSettings(settings)
                                        .build());
                        ch.pipeline().addLast(new H2RequestHandler());
                    }
                });
        serverChannel = bootstrap.bind(0).sync().channel();
    }

    /** 返回监听端口。 */
    int getPort() {
        return ((InetSocketAddress) serverChannel.localAddress()).getPort();
    }

    /** 停止 mock 服务。 */
    void stop() throws InterruptedException {
        if (serverChannel != null) {
            serverChannel.close().syncUninterruptibly();
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully().sync();
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully().sync();
        }
    }

    /** 设置自定义请求处理器。 */
    void setHandler(Function<FullHttpRequest, FullHttpResponse> handler) {
        this.handler = handler;
    }

    /** 设置 MAX_CONCURRENT_STREAMS（须在 start() 前调用）。 */
    void setMaxConcurrentStreams(int maxStreams) {
        this.maxConcurrentStreams = maxStreams;
    }

    /** 返回收到的所有请求。 */
    CopyOnWriteArrayList<ReceivedRequest> getReceivedRequests() {
        return receivedRequests;
    }

    /** 清空已收到的请求记录。 */
    void clearReceivedRequests() {
        receivedRequests.clear();
    }

    private FullHttpResponse defaultHandler(FullHttpRequest request) {
        String body = request.content().toString(CharsetUtil.UTF_8);
        String responseBody = body.isEmpty() ? "Hello from upstream!" : body;
        ByteBuf content = Unpooled.copiedBuffer(responseBody, CharsetUtil.UTF_8);
        FullHttpResponse response = new io.netty.handler.codec.http.DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, HttpResponseStatus.OK, content);
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=UTF-8");
        response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, content.readableBytes());
        return response;
    }

    /** 记录收到的请求信息。 */
    record ReceivedRequest(
            String method,
            String uri,
            io.netty.handler.codec.http.HttpHeaders headers,
            byte[] body) {
    }

    /** 每个 stream 的累积状态：HEADERS + DATA → FullHttpRequest。 */
    private static class StreamState {
        final String method;
        final String path;
        final DefaultHttpHeaders headers = new DefaultHttpHeaders();
        final CompositeByteBuf body;

        StreamState(String method, String path,
                io.netty.buffer.ByteBufAllocator alloc) {
            this.method = method;
            this.path = path;
            this.body = alloc.compositeBuffer();
        }
    }

    /**
     * H2 请求处理器：累积 HEADERS + DATA 帧 → 构建 FullHttpRequest → 调用 handler
     * → 将 FullHttpResponse 转换为 H2 HEADERS + DATA 帧写回。
     */
    private class H2RequestHandler extends ChannelInboundHandlerAdapter {

        private final ConcurrentHashMap<Integer, StreamState> streams =
                new ConcurrentHashMap<>();

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (msg instanceof Http2HeadersFrame headersFrame) {
                handleHeaders(ctx, headersFrame);
                return;
            }
            if (msg instanceof Http2DataFrame dataFrame) {
                handleData(ctx, dataFrame);
                return;
            }
            ctx.fireChannelRead(msg);
        }

        private void handleHeaders(ChannelHandlerContext ctx,
                Http2HeadersFrame frame) {
            int streamId = frame.stream().id();
            Http2Headers h2h = frame.headers();

            String method = h2h.method() != null
                    ? h2h.method().toString() : "GET";
            String path = h2h.path() != null
                    ? h2h.path().toString() : "/";

            StreamState state = new StreamState(method, path, ctx.alloc());

            // 将 H2 头部转为 H1 头部（去除伪头部）
            h2h.forEach(entry -> {
                String name = entry.getKey().toString();
                if (!name.startsWith(":")) {
                    state.headers.add(name, entry.getValue().toString());
                }
            });
            // 将 :authority 映射为 Host
            if (h2h.authority() != null) {
                state.headers.set("host", h2h.authority().toString());
            }

            streams.put(streamId, state);

            if (frame.isEndStream()) {
                dispatchRequest(ctx, streamId, state);
            }
        }

        private void handleData(ChannelHandlerContext ctx,
                Http2DataFrame frame) {
            int streamId = frame.stream().id();
            StreamState state = streams.get(streamId);
            if (state == null) {
                frame.release();
                return;
            }
            ByteBuf content = frame.content();
            if (content.isReadable()) {
                state.body.addComponent(true, content.retain());
            }
            frame.release();

            if (frame.isEndStream()) {
                dispatchRequest(ctx, streamId, state);
            }
        }

        private void dispatchRequest(ChannelHandlerContext ctx,
                int streamId, StreamState state) {
            streams.remove(streamId);

            // 构建 FullHttpRequest
            FullHttpRequest h1Request = new DefaultFullHttpRequest(
                    HttpVersion.HTTP_1_1,
                    HttpMethod.valueOf(state.method),
                    state.path,
                    state.body,
                    state.headers,
                    new DefaultHttpHeaders());

            // 记录请求
            byte[] bodyBytes = new byte[state.body.readableBytes()];
            state.body.getBytes(state.body.readerIndex(), bodyBytes);
            receivedRequests.add(new ReceivedRequest(
                    state.method, state.path, state.headers.copy(), bodyBytes));

            // 调用 handler
            FullHttpResponse h1Response;
            try {
                h1Response = handler.apply(h1Request);
            } finally {
                h1Request.release();
            }

            // 将 FullHttpResponse 转换为 H2 帧写回
            writeH2Response(ctx, streamId, h1Response);
        }

        private void writeH2Response(ChannelHandlerContext ctx,
                int streamId, FullHttpResponse h1Response) {
            Http2ConnectionHandler h2Handler =
                    ctx.pipeline().get(Http2ConnectionHandler.class);
            Http2ConnectionEncoder encoder = h2Handler.encoder();
            ChannelHandlerContext codecCtx =
                    ctx.pipeline().context(Http2ConnectionHandler.class);

            // 构造 H2 响应头
            Http2Headers h2Headers = new DefaultHttp2Headers();
            h2Headers.status(String.valueOf(h1Response.status().code()));
            h1Response.headers().forEach(entry -> {
                String name = entry.getKey().toLowerCase(java.util.Locale.ROOT);
                // 跳过 H1 特有的 hop-by-hop 头
                if (!"connection".equals(name) && !"keep-alive".equals(name)
                        && !"transfer-encoding".equals(name)) {
                    h2Headers.add(name, entry.getValue());
                }
            });

            ByteBuf responseBody = h1Response.content();
            boolean hasBody = responseBody.isReadable();

            // 写 HEADERS 帧
            encoder.writeHeaders(codecCtx, streamId, h2Headers, 0,
                    !hasBody, codecCtx.newPromise());

            // 写 DATA 帧（如果有 body）
            if (hasBody) {
                encoder.writeData(codecCtx, streamId,
                        responseBody.retain(), 0, true,
                        codecCtx.newPromise());
            }
            codecCtx.flush();
            h1Response.release();
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx,
                Throwable cause) {
            ctx.close();
        }
    }
}
