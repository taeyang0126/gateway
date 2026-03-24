package com.lei.gateway.core.proxy;

import com.lei.gateway.core.config.GatewayProperties;
import com.lei.gateway.core.config.ObservabilityProperties;
import com.lei.gateway.core.config.RequestLimitProperties;
import com.lei.gateway.core.config.RouteResolver;
import com.lei.gateway.core.config.SecurityProperties;
import com.lei.gateway.core.observability.AccessLogWriter;
import com.lei.gateway.core.observability.MetricsCollector;
import com.lei.gateway.core.observability.TraceContextHandler;
import com.lei.gateway.core.security.GatewaySecurityProcessor;
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
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Netty 服务生命周期管理，与 Spring Boot 集成。
 *
 * <p>实现 {@link SmartLifecycle}，Spring 容器就绪后启动 Netty，
 * 应用关闭时优雅停机。
 */
@Component
public class NettyServerBootstrap implements SmartLifecycle {

    private static final Logger log =
            LoggerFactory.getLogger(NettyServerBootstrap.class);

    private final GatewayProperties gatewayProperties;
    private final RequestLimitProperties requestLimitProperties;
    private final ObservabilityProperties observabilityProperties;
    private final RouteResolver routeResolver;
    private final UpstreamConnectionPool connectionPool;
    private final MetricsCollector metricsCollector;
    private final AccessLogWriter accessLogWriter;
    private final SecurityProperties securityProperties;
    private final ApplicationContext applicationContext;
    private final EventLoopGroup workerGroup;

    private EventLoopGroup bossGroup;
    private Channel serverChannel;
    private final AtomicInteger activeConnections = new AtomicInteger(0);
    private volatile boolean running;

    /** 创建 NettyServerBootstrap。 */
    public NettyServerBootstrap(GatewayProperties gatewayProperties,
            RequestLimitProperties requestLimitProperties,
            ObservabilityProperties observabilityProperties,
            RouteResolver routeResolver,
            UpstreamConnectionPool connectionPool,
            MetricsCollector metricsCollector,
            AccessLogWriter accessLogWriter,
            SecurityProperties securityProperties,
            ApplicationContext applicationContext,
            EventLoopGroup workerGroup) {
        this.gatewayProperties = gatewayProperties;
        this.requestLimitProperties = requestLimitProperties;
        this.observabilityProperties = observabilityProperties;
        this.routeResolver = routeResolver;
        this.connectionPool = connectionPool;
        this.metricsCollector = metricsCollector;
        this.accessLogWriter = accessLogWriter;
        this.securityProperties = securityProperties;
        this.applicationContext = applicationContext;
        this.workerGroup = workerGroup;
    }

    @Override
    public void start() {
        bossGroup = new NioEventLoopGroup(1);

        Instant serverStartTime = Instant.now();
        TraceContextHandler traceContextHandler =
                new TraceContextHandler(observabilityProperties);
        GatewaySecurityProcessor securityProcessor =
                new GatewaySecurityProcessor(securityProperties, metricsCollector);
        RoutingHandler routingHandler = new RoutingHandler(
                routeResolver, requestLimitProperties, connectionPool,
                metricsCollector, accessLogWriter, observabilityProperties,
                securityProcessor,
                activeConnections, serverStartTime);
        GatewayChannelInitializer initializer = new GatewayChannelInitializer(
                traceContextHandler, routingHandler, requestLimitProperties);

        ServerBootstrap bootstrap = new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .option(ChannelOption.SO_BACKLOG, 1024)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childHandler(initializer);

        try {
            serverChannel = bootstrap.bind(gatewayProperties.getPort())
                    .sync().channel();
            running = true;

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

    @Override
    public void stop() {
        log.info("Netty 网关开始关闭...");
        running = false;
        if (serverChannel != null) {
            serverChannel.close().syncUninterruptibly();
        }
        connectionPool.closeAll();
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
        }
        log.info("Netty 网关已关闭");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** 返回活跃连接数计数器（供 Handler 使用）。 */
    public AtomicInteger getActiveConnections() {
        return activeConnections;
    }
}
