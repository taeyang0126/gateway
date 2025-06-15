package com.lei.java.gateway.server;

import com.alibaba.nacos.api.exception.NacosException;
import com.lei.java.gateway.client.constants.GatewayConstant;
import com.lei.java.gateway.server.auth.DefaultAuthService;
import com.lei.java.gateway.server.codec.GatewayMessageCodec;
import com.lei.java.gateway.server.handler.AuthHandler;
import com.lei.java.gateway.server.handler.GatewayServerHandler;
import com.lei.java.gateway.server.route.DefaultRouteService;
import com.lei.java.gateway.server.route.DefaultServiceRegistry;
import com.lei.java.gateway.server.route.RouteService;
import com.lei.java.gateway.server.route.ServiceInstance;
import com.lei.java.gateway.server.route.ServiceRegistry;
import com.lei.java.gateway.server.route.connection.ConnectionManager;
import com.lei.java.gateway.server.route.connection.DefaultConnectionManager;
import com.lei.java.gateway.server.route.loadbalancer.LoadBalancer;
import com.lei.java.gateway.server.route.loadbalancer.RoundRobinLoadBalancer;
import com.lei.java.gateway.server.route.nacos.NacosConfig;
import com.lei.java.gateway.server.route.nacos.NacosConfigLoader;
import com.lei.java.gateway.server.route.nacos.NacosServiceRegistry;
import com.lei.java.gateway.server.session.DefaultSessionManager;
import com.lei.java.gateway.server.session.SessionManager;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.timeout.IdleStateHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class GatewayServer {
    private static final Logger logger = LoggerFactory.getLogger(GatewayServer.class);

    private final int port;
    private final EventLoopGroup bossGroup;
    private final EventLoopGroup workerGroup;
    private final SessionManager sessionManager;
    private final RouteService routeService;
    private final AuthHandler authHandler;
    private final ServiceRegistry registry;

    public GatewayServer(int port) {
        this.port = port;
        this.bossGroup = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        this.workerGroup = new MultiThreadIoEventLoopGroup(NioIoHandler.newFactory());
        this.sessionManager = new DefaultSessionManager();
        ConnectionManager connectionManager = new DefaultConnectionManager();
        registry = new DefaultServiceRegistry(connectionManager);
        LoadBalancer loadBalancer = new RoundRobinLoadBalancer();
        this.routeService = new DefaultRouteService(registry, loadBalancer, connectionManager);
        this.authHandler = new AuthHandler(new DefaultAuthService(), sessionManager);
    }

    public GatewayServer(int port, ServiceRegistry registry, ConnectionManager connectionManager) {
        this(port, new DefaultRouteService(registry, new RoundRobinLoadBalancer(), connectionManager));
    }

    public GatewayServer(int port, RouteService routeService) {
        this.port = port;
        this.bossGroup = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        this.workerGroup = new MultiThreadIoEventLoopGroup(NioIoHandler.newFactory());
        this.sessionManager = new DefaultSessionManager();
        this.routeService = routeService;
        this.authHandler = new AuthHandler(new DefaultAuthService(), sessionManager);
        this.registry = routeService.getServiceRegistry();
    }

    public void start() throws Exception {
        start(new CompletableFuture<>());
    }

    public void start(CompletableFuture<Void> completableFuture) throws Exception {
        logger.info("Starting Gateway Server on port: {}", port);

        try {
            ServerBootstrap b = new ServerBootstrap();
            b.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .option(ChannelOption.SO_BACKLOG, 128)
                    .option(ChannelOption.SO_REUSEADDR, true)
                    .childOption(ChannelOption.SO_KEEPALIVE, true)
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) throws Exception {
                            logger.debug("New connection from: {}", ch.remoteAddress());
                            ChannelPipeline p = ch.pipeline();
                            // 添加空闲检测，60秒没有读取到数据则判定为空闲
                            p.addLast(new IdleStateHandler(60, 0, 0, TimeUnit.SECONDS));
                            // 添加消息编解码器
                            p.addLast(new GatewayMessageCodec());
                            // auth handler
                            p.addLast(authHandler);
                            // 添加网关处理器
                            p.addLast(new GatewayServerHandler(sessionManager, routeService));
                        }
                    });

            // 绑定端口并启动服务器
            ChannelFuture f = b.bind(port)
                    .addListener((ChannelFutureListener) channelFuture -> {
                        // 如果是 nacos，则将本机注册到 nacos 上
                        if (registry != null && registry instanceof NacosServiceRegistry) {
                            InetSocketAddress socketAddress = (InetSocketAddress) channelFuture.channel().localAddress();
                            String serverHost = socketAddress.getHostString();
                            int serverPort = socketAddress.getPort();
                            registry.registerService(GatewayConstant.SERVER_NAME, new ServiceInstance(serverHost, serverPort));
                            logger.info("Gateway register nacos success: host={}, port={}", serverHost, serverPort);
                        }
                    })
                    .sync();

            logger.info("Gateway Server started successfully on port: {}", port);
            completableFuture.complete(null);

            // 等待服务器关闭
            f.channel().closeFuture().sync();
        } catch (Exception e) {
            logger.error("Failed to start Gateway Server", e);
            completableFuture.completeExceptionally(e);
            throw e;
        } finally {
            // 优雅关闭
            shutdown();
        }
    }

    public void shutdown() {
        logger.info("Shutting down Gateway Server...");
        // 关闭会话管理器
        if (sessionManager instanceof DefaultSessionManager) {
            ((DefaultSessionManager) sessionManager).shutdown();
        }
        // 关闭线程组
        bossGroup.shutdownGracefully();
        workerGroup.shutdownGracefully();
        logger.info("Gateway Server shutdown completed");
    }

    public void registerService(String bizType, String host, int port) {
        ServiceRegistry serviceRegistry = this.routeService.getServiceRegistry();
        serviceRegistry.registerService(bizType, new ServiceInstance(host, port));
    }

    public static void main(String[] args) throws Exception {
        int port = 8888;
        if (args.length > 0) {
            port = Integer.parseInt(args[0]);
        }

        NacosConfig config = NacosConfigLoader.load("nacos.properties");
        ServiceRegistry registry = new NacosServiceRegistry(config);
        new GatewayServer(port, registry, new DefaultConnectionManager())
                .start();
    }
} 