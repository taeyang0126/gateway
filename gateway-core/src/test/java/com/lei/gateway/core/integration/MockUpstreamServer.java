package com.lei.gateway.core.integration;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.CharsetUtil;
import java.net.InetSocketAddress;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * 轻量级 mock upstream HTTP 服务，基于 Netty 实现。
 *
 * <p>默认行为：echo 请求体，返回 200。可通过 {@link #setHandler} 自定义。
 */
class MockUpstreamServer {

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;
    private volatile Function<FullHttpRequest, FullHttpResponse> handler;
    private final CopyOnWriteArrayList<ReceivedRequest> receivedRequests =
            new CopyOnWriteArrayList<>();

    MockUpstreamServer() {
        this.handler = this::defaultHandler;
    }

    /** 启动 mock 服务，绑定随机端口。 */
    void start() throws InterruptedException {
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup(1);
        ServerBootstrap bootstrap = new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline()
                                .addLast(new HttpServerCodec())
                                .addLast(new HttpObjectAggregator(
                                        10 * 1024 * 1024))
                                .addLast(new RequestHandler());
                    }
                });
        serverChannel = bootstrap.bind(0).sync().channel();
    }

    /** 返回监听端口。 */
    int getPort() {
        return ((InetSocketAddress)
                serverChannel.localAddress()).getPort();
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
    void setHandler(
            Function<FullHttpRequest, FullHttpResponse> handler) {
        this.handler = handler;
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
        String responseBody = body.isEmpty()
                ? "Hello from upstream!" : body;
        ByteBuf content = Unpooled.copiedBuffer(
                responseBody, CharsetUtil.UTF_8);
        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, HttpResponseStatus.OK, content);
        response.headers().set(HttpHeaderNames.CONTENT_TYPE,
                "text/plain; charset=UTF-8");
        response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH,
                content.readableBytes());
        return response;
    }

    /** 记录收到的请求信息。 */
    record ReceivedRequest(
            String method,
            String uri,
            io.netty.handler.codec.http.HttpHeaders headers,
            byte[] body) {
    }

    private class RequestHandler
            extends SimpleChannelInboundHandler<FullHttpRequest> {

        @Override
        protected void channelRead0(ChannelHandlerContext ctx,
                FullHttpRequest request) {
            // 记录请求
            byte[] bodyBytes = new byte[
                    request.content().readableBytes()];
            request.content().getBytes(
                    request.content().readerIndex(), bodyBytes);
            receivedRequests.add(new ReceivedRequest(
                    request.method().name(),
                    request.uri(),
                    request.headers().copy(),
                    bodyBytes));

            FullHttpResponse response = handler.apply(
                    request.retain());
            try {
                boolean keepAlive = HttpUtil.isKeepAlive(request);
                if (keepAlive) {
                    response.headers().set(
                            HttpHeaderNames.CONNECTION,
                            HttpHeaderValues.KEEP_ALIVE);
                    ctx.writeAndFlush(response);
                } else {
                    response.headers().set(
                            HttpHeaderNames.CONNECTION,
                            HttpHeaderValues.CLOSE);
                    ctx.writeAndFlush(response)
                            .addListener(
                                    ChannelFutureListener.CLOSE);
                }
            } finally {
                request.release();
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx,
                Throwable cause) {
            ctx.close();
        }
    }
}
