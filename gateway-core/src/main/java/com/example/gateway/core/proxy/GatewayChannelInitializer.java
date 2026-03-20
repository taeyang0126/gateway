package com.example.gateway.core.proxy;

import com.example.gateway.core.config.RequestLimitProperties;
import com.example.gateway.core.observability.TraceContextHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.timeout.IdleStateHandler;
import java.util.concurrent.TimeUnit;

/**
 * 配置每个新连接的 ChannelPipeline。
 *
 * <p>Pipeline 组成：
 * HttpServerCodec → IdleStateHandler → TraceContextHandler → RoutingHandler
 */
public class GatewayChannelInitializer extends ChannelInitializer<SocketChannel> {

    private final TraceContextHandler traceContextHandler;
    private final RoutingHandler routingHandler;
    private final int timeoutSeconds;

    /** 创建 GatewayChannelInitializer。 */
    public GatewayChannelInitializer(TraceContextHandler traceContextHandler,
            RoutingHandler routingHandler,
            RequestLimitProperties requestLimitProperties) {
        this.traceContextHandler = traceContextHandler;
        this.routingHandler = routingHandler;
        this.timeoutSeconds = requestLimitProperties.getTimeoutSeconds();
    }

    @Override
    protected void initChannel(SocketChannel ch) {
        ch.pipeline()
                .addLast("httpCodec", new HttpServerCodec())
                .addLast("idleState", new IdleStateHandler(
                        timeoutSeconds, 0, 0, TimeUnit.SECONDS))
                .addLast("traceContext", traceContextHandler)
                .addLast("routing", routingHandler);
    }
}
