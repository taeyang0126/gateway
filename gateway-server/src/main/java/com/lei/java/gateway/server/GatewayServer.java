/*
 * Copyright (c) 2025 The gateway Project
 * https://github.com/taeyang0126/gateway
 *
 * Licensed under the MIT License.
 * You may obtain a copy of the License at
 *
 *     https://opensource.org/licenses/MIT
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.lei.java.gateway.server;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

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
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import com.lei.java.gateway.client.constants.GatewayConstant;
import com.lei.java.gateway.server.auth.DefaultAuthService;
import com.lei.java.gateway.server.codec.GatewayMessageCodec;
import com.lei.java.gateway.server.config.GatewayConfiguration;
import com.lei.java.gateway.server.constants.CacheConstant;
import com.lei.java.gateway.server.handler.AuthHandler;
import com.lei.java.gateway.server.handler.GatewayServerHandler;
import com.lei.java.gateway.server.protocol.GatewayHeartbeat;
import com.lei.java.gateway.server.route.DefaultRouteService;
import com.lei.java.gateway.server.route.DefaultServiceRegistry;
import com.lei.java.gateway.server.route.RouteService;
import com.lei.java.gateway.server.route.ServiceInstance;
import com.lei.java.gateway.server.route.ServiceRegistry;
import com.lei.java.gateway.server.route.connection.ConnectionManager;
import com.lei.java.gateway.server.route.connection.DefaultConnectionManager;
import com.lei.java.gateway.server.route.loadbalancer.LoadBalancer;
import com.lei.java.gateway.server.route.loadbalancer.RandomLoadBalancer;
import com.lei.java.gateway.server.route.loadbalancer.RoundRobinLoadBalancer;
import com.lei.java.gateway.server.route.nacos.NacosServiceRegistry;
import com.lei.java.gateway.server.session.DefaultSessionManager;
import com.lei.java.gateway.server.session.SessionManager;

public class GatewayServer implements DisposableBean {
    private static final Logger logger = LoggerFactory.getLogger(GatewayServer.class);

    private static final int HEARTBEAT_INTERVAL_SECONDS = 30;
    private static final int HEARTBEAT_TIMEOUT_SECOND = 45;
    private final int port;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private final SessionManager sessionManager;
    private final RouteService routeService;
    private final AuthHandler authHandler;
    private final ServiceRegistry registry;
    private RedissonClient redissonClient;

    public GatewayServer(int port) {
        this.port = port;
        ConnectionManager connectionManager = new DefaultConnectionManager();
        this.registry = new DefaultServiceRegistry(connectionManager);
        this.sessionManager = new DefaultSessionManager();
        this.routeService =
                new DefaultRouteService(registry, new RoundRobinLoadBalancer(), connectionManager);
        this.authHandler = new AuthHandler(new DefaultAuthService(), sessionManager);
    }

    public GatewayServer(int port, RouteService routeService) {
        this.port = port;
        this.sessionManager = new DefaultSessionManager();
        this.routeService = routeService;
        this.authHandler = new AuthHandler(new DefaultAuthService(), sessionManager);
        this.registry = routeService.getServiceRegistry();
    }

    public GatewayServer(int port, ServiceRegistry registry, ConnectionManager connectionManager) {
        this(port, registry, connectionManager, new RandomLoadBalancer());
    }

    public GatewayServer(
            int port,
            ServiceRegistry registry,
            ConnectionManager connectionManager,
            LoadBalancer loadBalancer) {
        this.port = port;
        this.sessionManager = new DefaultSessionManager();
        this.routeService = new DefaultRouteService(registry, loadBalancer, connectionManager);
        this.authHandler = new AuthHandler(new DefaultAuthService(), sessionManager);
        this.registry = registry;
    }

    public GatewayServer(
            int port,
            SessionManager sessionManager,
            RouteService routeService,
            AuthHandler authHandler,
            ServiceRegistry registry,
            RedissonClient redissonClient) {
        this.port = port;
        this.sessionManager = sessionManager;
        this.routeService = routeService;
        this.authHandler = authHandler;
        this.registry = registry;
        this.redissonClient = redissonClient;
    }

    public void start() throws Exception {
        start(new CompletableFuture<>());
    }

    public void start(CompletableFuture<Void> completableFuture) throws Exception {
        logger.info("Starting Gateway Server on port: {}", port);

        this.bossGroup = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        this.workerGroup = new MultiThreadIoEventLoopGroup(NioIoHandler.newFactory());

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

            // 构建元数据
            final Map<String, String> metadata = new HashMap<>();
            final String nodeId = UUID.randomUUID()
                    .toString();
            metadata.put(GatewayConstant.NODE, nodeId);

            // 绑定端口并启动服务器
            ChannelFuture f = b.bind(port)
                    .addListener((ChannelFutureListener) channelFuture -> {
                        // 如果是 nacos，则将本机注册到 nacos 上
                        if (registry != null && registry instanceof NacosServiceRegistry) {
                            InetSocketAddress socketAddress =
                                    (InetSocketAddress) channelFuture.channel()
                                            .localAddress();
                            String serverHost = socketAddress.getHostString();
                            int serverPort = socketAddress.getPort();
                            metadata.put(GatewayConstant.HOST, serverHost);
                            metadata.put(GatewayConstant.PORT, String.valueOf(serverPort));

                            registry.registerService(GatewayConstant.SERVER_NAME,
                                    new ServiceInstance(serverHost, serverPort, metadata));
                            logger.info("Gateway register nacos success: host={}, port={}",
                                    serverHost,
                                    serverPort);
                        }

                        // 如果支持 redis，定时 30s 上报心跳
                        if (this.redissonClient != null) {
                            channelFuture.channel()
                                    .eventLoop()
                                    .scheduleAtFixedRate(() -> {
                                        GatewayHeartbeat heartbeat = GatewayHeartbeat.builder()
                                                .node(nodeId)
                                                .lastHeartbeatTime(System.currentTimeMillis())
                                                .build();
                                        RBucket<GatewayHeartbeat> bucket = redissonClient.getBucket(
                                                String.format(CacheConstant.GATEWAY_NODE_KEY,
                                                        nodeId));
                                        bucket.set(heartbeat,
                                                Duration.ofSeconds(HEARTBEAT_TIMEOUT_SECOND));
                                        logger.info("Gateway heartbeat success: nodeId={}", nodeId);
                                    }, 0, HEARTBEAT_INTERVAL_SECONDS, TimeUnit.SECONDS);
                        }
                    })
                    .sync();

            logger.info("Gateway Server started successfully on port: {}", port);
            completableFuture.complete(null);

            // 等待服务器关闭
            f.channel()
                    .closeFuture()
                    .sync();
        } catch (Exception e) {
            logger.error("Failed to start Gateway Server", e);
            completableFuture.completeExceptionally(e);
            throw e;
        }
    }

    public void shutdown() {
        logger.info("Shutting down Gateway Server...");
        // 关闭会话管理器
        if (sessionManager instanceof DefaultSessionManager) {
            ((DefaultSessionManager) sessionManager).shutdown();
        }
        // 关闭线程组
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
        }
        logger.info("Gateway Server shutdown completed");
    }

    public void registerService(String bizType, String host, int port) {
        ServiceRegistry serviceRegistry = this.routeService.getServiceRegistry();
        serviceRegistry.registerService(bizType, new ServiceInstance(host, port));
    }

    @Override
    public void destroy() {
        shutdown();
    }

    public static void main(String[] args) throws Exception {
        // 创建Spring容器
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        // 设置扫描包路径
        context.register(GatewayConfiguration.class);
        // 刷新容器
        context.refresh();

        // 获取GatewayServer实例并启动
        GatewayServer server = context.getBean(GatewayServer.class);

        // 添加JVM关闭钩子，确保应用优雅关闭
        Runtime.getRuntime()
                .addShutdownHook(new Thread(() -> {
                    try {
                        context.close();
                    } catch (Exception e) {
                        logger.error("Error closing Spring context", e);
                    }
                }));

        server.start();
    }
}