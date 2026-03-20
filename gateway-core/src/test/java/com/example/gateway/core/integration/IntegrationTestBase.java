package com.example.gateway.core.integration;

import com.example.gateway.core.config.ConnectionPoolProperties;
import com.example.gateway.core.config.GatewayProperties;
import com.example.gateway.core.config.ObservabilityProperties;
import com.example.gateway.core.config.RequestLimitProperties;
import com.example.gateway.core.config.Route;
import com.example.gateway.core.config.RouteResolver;
import com.example.gateway.core.observability.AccessLogWriter;
import com.example.gateway.core.observability.MetricsCollector;
import com.example.gateway.core.observability.TraceContextHandler;
import com.example.gateway.core.proxy.GatewayChannelInitializer;
import com.example.gateway.core.proxy.RoutingHandler;
import com.example.gateway.core.proxy.UpstreamConnectionPool;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

/**
 * 集成测试基类，启动真实的 Netty 网关和 mock upstream 服务。
 */
abstract class IntegrationTestBase {

    protected MockUpstreamServer upstreamServer;
    protected int upstreamPort;
    protected int gatewayPort;
    protected HttpClient httpClient;

    protected EventLoopGroup bossGroup;
    protected EventLoopGroup workerGroup;
    protected Channel serverChannel;

    protected GatewayProperties gatewayProperties;
    protected RequestLimitProperties requestLimitProperties;
    protected ConnectionPoolProperties connectionPoolProperties;
    protected ObservabilityProperties observabilityProperties;
    protected PrometheusMeterRegistry meterRegistry;
    protected MetricsCollector metricsCollector;
    protected AccessLogWriter accessLogWriter;
    protected UpstreamConnectionPool connectionPool;
    protected AtomicInteger activeConnections;

    @BeforeEach
    void setUpBase() throws Exception {
        // 1. 启动 mock upstream
        upstreamServer = createUpstreamServer();
        upstreamServer.start();
        upstreamPort = upstreamServer.getPort();

        // 2. 配置
        gatewayProperties = createGatewayProperties();
        requestLimitProperties = createRequestLimitProperties();
        connectionPoolProperties = createConnectionPoolProperties();
        observabilityProperties = createObservabilityProperties();

        // 3. 可观测性
        meterRegistry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        metricsCollector = new MetricsCollector(
                meterRegistry, observabilityProperties);
        accessLogWriter = new AccessLogWriter(observabilityProperties);

        // 4. Netty 基础设施
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup(2);
        connectionPool = new UpstreamConnectionPool(
                connectionPoolProperties, metricsCollector, workerGroup);
        activeConnections = new AtomicInteger(0);

        // 5. 注册指标
        metricsCollector.registerActiveConnections(activeConnections);
        metricsCollector.registerJvmMetrics();
        metricsCollector.registerNettyMetrics(
                workerGroup, ByteBufAllocator.DEFAULT);

        // 6. 启动网关
        startGateway();

        // 7. HTTP 客户端
        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    @AfterEach
    void tearDownBase() throws Exception {
        if (serverChannel != null) {
            serverChannel.close().syncUninterruptibly();
        }
        if (connectionPool != null) {
            connectionPool.closeAll();
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully().sync();
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully().sync();
        }
        if (upstreamServer != null) {
            upstreamServer.stop();
        }
        if (httpClient != null) {
            httpClient.close();
        }
    }

    /** 子类可覆盖以自定义 upstream 行为。 */
    protected MockUpstreamServer createUpstreamServer() {
        return new MockUpstreamServer();
    }

    private void startGateway() throws InterruptedException {
        RouteResolver routeResolver = new RouteResolver(gatewayProperties);
        Instant startTime = Instant.now();
        TraceContextHandler traceHandler =
                new TraceContextHandler(observabilityProperties);
        RoutingHandler routingHandler = new RoutingHandler(
                routeResolver, requestLimitProperties, connectionPool,
                metricsCollector, accessLogWriter, observabilityProperties,
                activeConnections, startTime);
        GatewayChannelInitializer initializer =
                new GatewayChannelInitializer(
                        traceHandler, routingHandler,
                        requestLimitProperties);

        ServerBootstrap bootstrap = new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .option(ChannelOption.SO_BACKLOG, 128)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childHandler(initializer);

        serverChannel = bootstrap.bind(0).sync().channel();
        gatewayPort = ((InetSocketAddress)
                serverChannel.localAddress()).getPort();
    }

    /** 构建网关 URL。 */
    protected URI gatewayUri(String path) {
        return URI.create("http://localhost:" + gatewayPort + path);
    }

    /** 默认网关配置。子类可覆盖。 */
    protected GatewayProperties createGatewayProperties() {
        GatewayProperties props = new GatewayProperties();
        props.setPort(0);
        List<Route> routes = new ArrayList<>();
        routes.add(createRoute("example-service",
                "/api/example",
                "http://localhost:" + upstreamPort));
        props.setRoutes(routes);
        return props;
    }

    /** 默认请求限制配置。子类可覆盖。 */
    protected RequestLimitProperties createRequestLimitProperties() {
        RequestLimitProperties props = new RequestLimitProperties();
        props.setMaxRequestSize(10240L); // 10KB for testing
        props.setTimeoutSeconds(5);
        return props;
    }

    /** 默认连接池配置。子类可覆盖。 */
    protected ConnectionPoolProperties createConnectionPoolProperties() {
        ConnectionPoolProperties props = new ConnectionPoolProperties();
        props.setMaxConnectionsPerHost(5);
        props.setMaxIdleTimeSeconds(30);
        props.setSlowConnectThresholdMillis(50);
        props.setConnectTimeoutMillis(2000);
        return props;
    }

    /** 默认可观测性配置。子类可覆盖。 */
    protected ObservabilityProperties createObservabilityProperties() {
        ObservabilityProperties props = new ObservabilityProperties();
        props.setMetricsEnabled(true);
        props.setAccessLogEnabled(true);
        props.setAccessLogLevel("INFO");
        props.setTracingEnabled(true);
        return props;
    }

    /** 创建路由。 */
    protected static Route createRoute(String id, String pathPrefix,
            String upstream) {
        Route route = new Route();
        route.setId(id);
        route.setPathPrefix(pathPrefix);
        route.setUpstream(upstream);
        return route;
    }
}
