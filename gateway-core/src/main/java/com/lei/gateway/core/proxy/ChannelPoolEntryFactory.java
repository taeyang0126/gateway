package com.lei.gateway.core.proxy;

import com.lei.gateway.pool.PoolEntryFactory;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpClientCodec;
import java.util.concurrent.CompletableFuture;

/**
 * 使用 Netty {@link Bootstrap} 创建到 upstream 的 TCP 连接，
 * 并包装为 {@link ChannelPoolEntry}。
 */
public class ChannelPoolEntryFactory implements PoolEntryFactory<ChannelPoolEntry> {

    private final Bootstrap bootstrap;
    private final String poolKey;

    /**
     * 创建 ChannelPoolEntryFactory。
     *
     * @param host         upstream 主机
     * @param port         upstream 端口
     * @param workerGroup  Netty worker EventLoopGroup
     * @param channelClass 传输层 Channel 类型
     * @param connectTimeoutMillis 连接超时（毫秒）
     */
    public ChannelPoolEntryFactory(String host, int port,
            EventLoopGroup workerGroup,
            Class<? extends Channel> channelClass,
            int connectTimeoutMillis) {
        this.poolKey = host + ":" + port;
        this.bootstrap = new Bootstrap()
                .group(workerGroup)
                .channel(channelClass)
                .option(io.netty.channel.ChannelOption.CONNECT_TIMEOUT_MILLIS,
                        connectTimeoutMillis)
                .remoteAddress(host, port)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(new HttpClientCodec());
                    }
                });
    }

    @Override
    public ChannelPoolEntry create() throws Exception {
        Channel channel = bootstrap.connect().sync().channel();
        return new ChannelPoolEntry(channel, poolKey);
    }

    @Override
    public CompletableFuture<ChannelPoolEntry> createAsync() {
        CompletableFuture<ChannelPoolEntry> future = new CompletableFuture<>();
        bootstrap.connect().addListener(f -> {
            if (f.isSuccess()) {
                Channel channel = ((io.netty.channel.ChannelFuture) f).channel();
                future.complete(new ChannelPoolEntry(channel, poolKey));
            } else {
                future.completeExceptionally(f.cause());
            }
        });
        return future;
    }
}
