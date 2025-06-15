package com.lei.java.gateway.server.route.connection;

import com.lei.java.gateway.server.protocol.GatewayMessage;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.util.ReferenceCountUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.concurrent.ThreadFactory;

/**
 * HTTP 连接处理器
 * 负责 GatewayMessage 和 HTTP 消息的转换
 */
public class HttpConnectionHandler extends ChannelDuplexHandler {

    private static final Logger logger = LoggerFactory.getLogger(HttpConnectionHandler.class);

    private final Connection connection;
    private final ArrayDeque<GatewayMessage> requests;
    private final ThreadFactory httpHandlerFactory;

    public HttpConnectionHandler(Connection connection) {
        this.connection = connection;
        this.requests = new ArrayDeque<>();
        this.httpHandlerFactory = Thread.ofVirtual().name("upstream-http-handler-", 0)
                .uncaughtExceptionHandler((t, e) -> logger.error("Uncaught exception", e))
                .factory();
    }


    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {

        if (!(msg instanceof GatewayMessage)) {
            logger.debug("[{}] Forwarding non-GatewayMessage directly.", ctx.channel().id());
            ctx.write(msg, promise);
            return;
        }

        httpHandlerFactory.newThread(() -> {
            GatewayMessage gatewayMessage = (GatewayMessage) msg;
            FullHttpRequest httpRequest = HttpProtocolConverter.toHttpRequest(gatewayMessage);
            // 转换为 HTTP 请求并发送
            ctx.writeAndFlush(httpRequest, promise)
                    .addListener(future -> {
                        if (future.isSuccess()) {
                            requests.add(gatewayMessage);
                        }
                    });
        }).start();

    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        httpHandlerFactory.newThread(() -> {
            try {
                if (msg instanceof FullHttpResponse) {
                    FullHttpResponse response = (FullHttpResponse) msg;
                    // 获取原始请求
                    GatewayMessage request = requests.poll();
                    if (request != null) {
                        // 转换为 GatewayMessage 并处理
                        GatewayMessage gatewayMessage = HttpProtocolConverter.toGatewayMessage(response, request);
                        connection.handleResponse(gatewayMessage);
                    }
                }
            } finally {
                ReferenceCountUtil.release(msg);
            }
        }).start();
    }

} 