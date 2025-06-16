package com.lei.java.gateway.server.route.connection;

import com.lei.java.gateway.server.route.ServiceInstance;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpObjectAggregator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadFactory;

/**
 * <p>
 * 默认的连接管理器
 * </p>
 *
 * @author 伍磊
 */
public class DefaultConnectionManager implements ConnectionManager {
    private static final Logger logger = LoggerFactory.getLogger(DefaultConnectionManager.class);

    private final Map<ServiceInstance, Connection> connections;
    // 一个静态的、线程安全的 Map，用于缓存正在进行的连接尝试。
    private final Map<ServiceInstance, CompletableFuture<Connection>> pendingConnections;
    private final EventLoopGroup workerGroup;
    private final Bootstrap bootstrap;
    private volatile boolean closed;
    private final ThreadFactory createConnectionFactory;

    public DefaultConnectionManager() {
        this.connections = new ConcurrentHashMap<>();
        this.pendingConnections = new ConcurrentHashMap<>();
        this.workerGroup = new MultiThreadIoEventLoopGroup(Runtime.getRuntime().availableProcessors() * 2,
                        NioIoHandler.newFactory());
        this.bootstrap = new Bootstrap();
        this.closed = false;

        createConnectionFactory = Thread.ofVirtual().name("upstream-connection-create-", 0)
                        .uncaughtExceptionHandler((t, e) -> logger.error("Uncaught exception", e)).factory();

        // 初始化Bootstrap
        bootstrap.group(workerGroup).channel(NioSocketChannel.class).option(ChannelOption.TCP_NODELAY, true)
                        .option(ChannelOption.SO_KEEPALIVE, true)
                        // 连接超时时间
                        .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, getConnectTimeoutMillis())
                        .handler(new ChannelInitializer<NioSocketChannel>() {
                            @Override
                            protected void initChannel(NioSocketChannel ch) {
                                ChannelPipeline pipeline = ch.pipeline();
                                pipeline.addLast(new HttpClientCodec());
                                pipeline.addLast(new HttpObjectAggregator(65536));
                            }
                        });
    }

    @Override
    public CompletableFuture<Connection> getConnection(ServiceInstance instance) {
        if (closed) {
            CompletableFuture<Connection> future = new CompletableFuture<>();
            future.completeExceptionally(new IllegalStateException("ConnectionManager is closed"));
            return future;
        }

        Connection connection = connections.get(instance);
        if (connection != null && connection.isActive()) {
            return CompletableFuture.completedFuture(connection);
        }

        return createConnection(instance);
    }

    private CompletableFuture<Connection> createConnection(ServiceInstance instance) {
        // 使用 computeIfAbsent 实现原子性的“检查并创建”。
        // 这个方法会保证对于同一个 instance，内部的 lambda 表达式只会被执行一次。
        return pendingConnections.computeIfAbsent(instance, key -> {

            // --- 这部分代码块是线程安全的，只有第一个线程会进入 ---

            logger.info("Creating new connection for {}", key);
            CompletableFuture<Connection> future = new CompletableFuture<>();

            createConnectionFactory.newThread(() -> {
                bootstrap.connect(key.getHost(), key.getPort()).addListener((ChannelFutureListener) f -> {
                    if (f.isSuccess()) {
                        Channel channel = f.channel();
                        Connection connection = new DefaultConnection(bootstrap, channel, key);
                        // 添加 HTTP 协议转换处理器
                        channel.pipeline().addLast(new HttpConnectionHandler(connection));
                        connections.put(key, connection);
                        future.complete(connection);
                    } else {
                        future.completeExceptionally(f.cause());
                    }

                    // 无论成功还是失败，都要从 map 中移除。 这样，下一次调用 createConnection 时就可以发起新的连接尝试。
                    pendingConnections.remove(key, future);
                });
            }).start();

            return future;
        });
    }

    @Override
    public void releaseConnection(Connection connection) {
        if (connection != null && !connection.isActive()) {
            removeConnection(connection);
        }
    }

    @Override
    public void removeConnection(Connection connection) {
        if (connection != null) {
            connections.remove(connection.getServiceInstance());
            connection.close();
        }
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            connections.values().forEach(Connection::close);
            connections.clear();
            workerGroup.shutdownGracefully();
        }
    }
}
