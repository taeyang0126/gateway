package com.lei.gateway.core.proxy;

import com.lei.gateway.pool.PoolEntryFactory;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http2.Http2FrameCodec;
import io.netty.handler.codec.http2.Http2FrameCodecBuilder;
import java.util.concurrent.CompletableFuture;

/**
 * 使用 Netty {@link Bootstrap} 创建到 upstream 的 H2（h2c 明文）连接，
 * 并包装为 {@link ChannelPoolEntry}。
 *
 * <p>Pipeline 配置：{@link Http2FrameCodec} + {@link H2ResponseDemuxHandler}。
 * 不使用 {@code Http2MultiplexHandler}，由 H2ResponseDemuxHandler 直接处理帧对象。
 * 采用 Prior Knowledge 模式，直接发送 H2 Connection Preface，不走 HTTP/1.1 Upgrade。
 */
public class ChannelPoolEntryFactory implements PoolEntryFactory<ChannelPoolEntry> {

    private final Bootstrap bootstrap;
    private final String poolKey;

    /**
     * 创建 ChannelPoolEntryFactory。
     *
     * @param host                 upstream 主机
     * @param port                 upstream 端口
     * @param workerGroup          Netty worker EventLoopGroup
     * @param channelClass         传输层 Channel 类型
     * @param connectTimeoutMillis 连接超时（毫秒）
     * @param connectionPool       上游连接池引用，供 H2ResponseDemuxHandler 使用
     */
    public ChannelPoolEntryFactory(String host, int port,
            EventLoopGroup workerGroup,
            Class<? extends Channel> channelClass,
            int connectTimeoutMillis,
            UpstreamConnectionPool connectionPool) {
        this.poolKey = host + ":" + port;
        this.bootstrap = new Bootstrap()
                .group(workerGroup)
                .channel(channelClass)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeoutMillis)
                .remoteAddress(host, port)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        Http2FrameCodec frameCodec = Http2FrameCodecBuilder.forClient()
                                .initialSettings(io.netty.handler.codec.http2.Http2Settings
                                        .defaultSettings())
                                .build();
                        ch.pipeline().addLast(frameCodec);
                        ch.pipeline().addLast(new H2ResponseDemuxHandler(connectionPool));
                    }
                });
    }

    @Override
    public CompletableFuture<ChannelPoolEntry> createAsync() {
        CompletableFuture<ChannelPoolEntry> future = new CompletableFuture<>();
        bootstrap.connect().addListener(cf -> {
            if (cf.isSuccess()) {
                Channel channel = ((io.netty.channel.ChannelFuture) cf).channel();
                future.complete(new ChannelPoolEntry(channel, poolKey));
            } else {
                future.completeExceptionally(cf.cause());
            }
        });
        return future;
    }
}
