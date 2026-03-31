package com.lei.gateway.proxy;

import com.lei.gateway.config.GatewayProperties;
import com.lei.gateway.config.ObservabilityProperties;
import com.lei.gateway.config.RequestLimitProperties;
import com.lei.gateway.observability.MetricsCollector;
import com.lei.gateway.observability.TraceContextHandler;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;

/**
 * Netty 服务启动器。
 *
 * <p>不再实现 {@code SmartLifecycle}，生命周期由
 * {@link ShutdownCoordinator} 统一管理。
 */
public class NettyServerBootstrap {

    private static final Logger log =
            LoggerFactory.getLogger(NettyServerBootstrap.class);

    private final GatewayProperties gatewayProperties;
    private final ObservabilityProperties observabilityProperties;
    private final RequestLimitProperties requestLimitProperties;
    private final MetricsCollector metricsCollector;
    private final RoutingContext routingContext;
    private final DrainHandler drainHandler;
    private final ApplicationContext applicationContext;
    private final EventLoopGroup workerGroup;

    private EventLoopGroup bossGroup;
    private Channel serverChannel;
    private final AtomicInteger activeConnections = new AtomicInteger(0);

    /** 创建 NettyServerBootstrap。 */
    public NettyServerBootstrap(GatewayProperties gatewayProperties,
            ObservabilityProperties observabilityProperties,
            RequestLimitProperties requestLimitProperties,
            MetricsCollector metricsCollector,
            RoutingContext routingContext,
            DrainHandler drainHandler,
            ApplicationContext applicationContext,
            EventLoopGroup workerGroup) {
        this.gatewayProperties = gatewayProperties;
        this.observabilityProperties = observabilityProperties;
        this.requestLimitProperties = requestLimitProperties;
        this.metricsCollector = metricsCollector;
        this.routingContext = routingContext;
        this.drainHandler = drainHandler;
        this.applicationContext = applicationContext;
        this.workerGroup = workerGroup;
    }

    /** 启动 Netty 服务，bind 端口并开始接受连接。 */
    public void start() {
        bossGroup = new NioEventLoopGroup(1);

        Instant serverStartTime = Instant.now();
        TraceContextHandler traceContextHandler =
                new TraceContextHandler(observabilityProperties);
        RoutingHandler routingHandler = new RoutingHandler(
                routingContext, activeConnections, serverStartTime);
        GatewayChannelInitializer initializer = new GatewayChannelInitializer(
                traceContextHandler, routingHandler, requestLimitProperties,
                drainHandler);

        ServerBootstrap bootstrap = new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .option(ChannelOption.SO_BACKLOG, 1024)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childHandler(initializer);

        try {
            serverChannel = bootstrap.bind(gatewayProperties.getPort())
                    .sync().channel();

            metricsCollector.registerActiveConnections(activeConnections);
            metricsCollector.registerJvmMetrics();
            metricsCollector.registerNettyMetrics(workerGroup,
                    ByteBufAllocator.DEFAULT);

            log.info("Netty 网关启动成功，监听端口: {}",
                    gatewayProperties.getPort());
        } catch (Exception e) {
            log.error("Netty 网关启动失败", e);
            if (bossGroup != null) {
                bossGroup.shutdownGracefully();
            }
            SpringApplication.exit(applicationContext, () -> 1);
        }
    }

    /**
     * 关闭 serverChannel 和 bossGroup，停止接受新 TCP 连接。
     *
     * <p>供 {@link ShutdownCoordinator} 在停机阶段1调用。
     */
    public void closeServerChannelAndBossGroup() {
        if (serverChannel != null) {
            serverChannel.close().syncUninterruptibly();
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
        }
    }

    /** 返回活跃连接数计数器（供 Handler 使用）。 */
    public AtomicInteger getActiveConnections() {
        return activeConnections;
    }
}
