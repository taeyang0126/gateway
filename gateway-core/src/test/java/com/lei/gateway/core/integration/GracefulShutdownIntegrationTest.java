package com.lei.gateway.core.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lei.gateway.core.config.ConnectionPoolProperties;
import com.lei.gateway.core.config.GatewayProperties;
import com.lei.gateway.core.config.HealthProperties;
import com.lei.gateway.core.config.ObservabilityProperties;
import com.lei.gateway.core.config.RequestLimitProperties;
import com.lei.gateway.core.config.Route;
import com.lei.gateway.core.config.RouteResolver;
import com.lei.gateway.core.config.ShutdownProperties;
import com.lei.gateway.core.observability.AccessLogWriter;
import com.lei.gateway.core.observability.MetricsCollector;
import com.lei.gateway.core.proxy.DrainHandler;
import com.lei.gateway.core.proxy.InFlightRequestTracker;
import com.lei.gateway.core.proxy.NettyServerBootstrap;
import com.lei.gateway.core.proxy.RoutingContext;
import com.lei.gateway.core.proxy.ShutdownCoordinator;
import com.lei.gateway.core.proxy.UpstreamConnectionPool;
import com.lei.gateway.core.proxy.WarmupRunner;
import com.lei.gateway.core.plugin.GatewayPluginProcessor;
import com.lei.gateway.core.plugin.PluginChain;
import com.lei.gateway.core.plugin.PluginConfigResolver;
import com.lei.gateway.core.plugin.PluginRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.CharsetUtil;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * 优雅停机端到端集成测试。
 *
 * <p>验证：发送请求 → 触发停机 → 在途请求完成 → 新请求被拒绝（503）。
 *
 * <p>不继承 {@link IntegrationTestBase}，因为需要通过 {@link ShutdownCoordinator}
 * 管理完整生命周期（包括 {@link NettyServerBootstrap}），而非手动 bind 端口。
 *
 * <p>Requirements: 3.1, 3.4, 3.5, 4.1
 */
class GracefulShutdownIntegrationTest {

    /** upstream 收到请求后延迟响应的毫秒数，用于模拟慢请求。 */
    private static final int SLOW_UPSTREAM_DELAY_MS = 2000;

    private MockUpstreamServer upstreamServer;
    private int upstreamPort;
    private int gatewayPort;

    private EventLoopGroup workerGroup;
    private UpstreamConnectionPool connectionPool;
    private InFlightRequestTracker inFlightTracker;
    private DrainHandler drainHandler;
    private ShutdownCoordinator shutdownCoordinator;
    private HttpClient httpClient;

    @BeforeEach
    void setUp() throws Exception {
        // 1. 启动 mock upstream（延迟响应）
        upstreamServer = new MockUpstreamServer();
        upstreamServer.start();
        upstreamPort = upstreamServer.getPort();

        // 2. 配置
        GatewayProperties gatewayProps = new GatewayProperties();
        gatewayPort = findAvailablePort();
        gatewayProps.setPort(gatewayPort);
        List<Route> routes = new ArrayList<>();
        Route route = new Route();
        route.setId("test-service");
        route.setPathPrefix("/api/test/**");
        route.setUpstream("http://localhost:" + upstreamPort);
        routes.add(route);
        gatewayProps.setRoutes(routes);

        RequestLimitProperties requestLimitProps = new RequestLimitProperties();
        requestLimitProps.setMaxRequestSize(10240L);
        requestLimitProps.setTimeoutSeconds(10);

        ConnectionPoolProperties poolProps = new ConnectionPoolProperties();
        poolProps.setMaxConnectionsPerHost(5);
        poolProps.setMaxIdleTimeSeconds(30);
        poolProps.setSlowConnectThresholdMillis(50);
        poolProps.setConnectTimeoutMillis(3000);

        ObservabilityProperties obsProps = new ObservabilityProperties();
        obsProps.setMetricsEnabled(true);
        obsProps.setAccessLogEnabled(true);
        obsProps.setTracingEnabled(false);


        ShutdownProperties shutdownProps = new ShutdownProperties();
        shutdownProps.setShutdownTimeoutSeconds(10);
        shutdownProps.setShutdownPollIntervalMillis(100);

        HealthProperties healthProps = new HealthProperties();
        healthProps.setStartupDelaySeconds(0);
        healthProps.setWarmupTimeoutSeconds(5);

        // 3. 基础设施
        PrometheusMeterRegistry meterRegistry =
                new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        MetricsCollector metricsCollector =
                new MetricsCollector(meterRegistry, obsProps);
        AccessLogWriter accessLogWriter =
                new AccessLogWriter(obsProps, new ObjectMapper());

        workerGroup = new NioEventLoopGroup(2);
        connectionPool = new UpstreamConnectionPool(
                poolProps, metricsCollector, workerGroup);

        inFlightTracker = new InFlightRequestTracker();
        drainHandler = new DrainHandler();

        GatewayPluginProcessor pluginProcessor = new GatewayPluginProcessor(
                new PluginRegistry(),
                new PluginConfigResolver(new PluginRegistry()),
                new PluginChain(metricsCollector),
                java.util.List.of());
        RouteResolver routeResolver = new RouteResolver(gatewayProps);

        RoutingContext routingCtx = new RoutingContext(
                routeResolver, requestLimitProps, connectionPool,
                metricsCollector, accessLogWriter, obsProps,
                pluginProcessor, inFlightTracker, drainHandler,
                healthProps);

        // 4. 使用 MockApplicationContext 创建 NettyServerBootstrap
        MockEnvironment env = new MockEnvironment();
        org.springframework.context.support.GenericApplicationContext appCtx =
                new org.springframework.context.support.GenericApplicationContext();
        appCtx.setEnvironment(env);
        appCtx.refresh();

        NettyServerBootstrap serverBootstrap = new NettyServerBootstrap(
                gatewayProps, obsProps, requestLimitProps,
                metricsCollector, routingCtx, drainHandler,
                appCtx, workerGroup);

        WarmupRunner warmupRunner = new WarmupRunner(
                routeResolver, gatewayProps, connectionPool,
                healthProps, new ObjectMapper());

        shutdownCoordinator = new ShutdownCoordinator(
                serverBootstrap, drainHandler, inFlightTracker,
                connectionPool, workerGroup, shutdownProps, warmupRunner);

        // 5. 启动（预热 + bind 端口）
        shutdownCoordinator.start();

        // 6. HTTP 客户端
        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (shutdownCoordinator != null && shutdownCoordinator.isRunning()) {
            shutdownCoordinator.stop();
        }
        if (upstreamServer != null) {
            upstreamServer.stop();
        }
        if (httpClient != null) {
            httpClient.close();
        }
    }

    /**
     * 验证正常请求在停机前能成功完成。
     * Requirements: 前置条件验证
     */
    @Test
    void requestSucceedsBeforeShutdown() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(gatewayUri("/api/test/hello"))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(
                request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("Hello from upstream!");
    }

    /**
     * 端到端验证优雅停机核心流程：
     * 1. 发送慢请求（upstream 延迟响应）
     * 2. 在请求处理中触发停机
     * 3. 验证在途请求正常完成（200）
     * 4. 验证停机后新请求被拒绝（503 或连接拒绝）
     *
     * Requirements: 3.1, 3.4, 3.5, 4.1
     */
    @Test
    void inFlightRequestCompletesAndNewRequestRejectedDuringShutdown()
            throws Exception {
        // 设置 upstream 延迟响应
        CountDownLatch upstreamReceived = new CountDownLatch(1);
        upstreamServer.setHandler(req -> {
            upstreamReceived.countDown();
            try {
                Thread.sleep(SLOW_UPSTREAM_DELAY_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            ByteBuf content = Unpooled.copiedBuffer(
                    "slow-response", CharsetUtil.UTF_8);
            FullHttpResponse resp = new DefaultFullHttpResponse(
                    HttpVersion.HTTP_1_1, HttpResponseStatus.OK, content);
            resp.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain");
            resp.headers().setInt(HttpHeaderNames.CONTENT_LENGTH,
                    content.readableBytes());
            return resp;
        });

        // 1. 异步发送慢请求
        AtomicReference<HttpResponse<String>> slowResponse =
                new AtomicReference<>();
        AtomicReference<Throwable> slowError = new AtomicReference<>();
        CompletableFuture<Void> slowRequestFuture =
                CompletableFuture.runAsync(() -> {
                    try {
                        HttpRequest request = HttpRequest.newBuilder()
                                .uri(gatewayUri("/api/test/slow"))
                                .GET()
                                .build();
                        slowResponse.set(httpClient.send(
                                request,
                                HttpResponse.BodyHandlers.ofString()));
                    } catch (Exception e) {
                        slowError.set(e);
                    }
                });

        // 2. 等待 upstream 收到请求，确认请求已在途
        assertThat(upstreamReceived.await(5, TimeUnit.SECONDS))
                .as("upstream 应收到请求")
                .isTrue();
        assertThat(inFlightTracker.getInFlightCount())
                .as("应有在途请求")
                .isGreaterThanOrEqualTo(1);

        // 3. 触发停机（异步，因为 stop 会阻塞等待在途请求完成）
        CompletableFuture<Void> shutdownFuture =
                CompletableFuture.runAsync(() -> shutdownCoordinator.stop());

        // 等待排空激活
        assertDrainActivatedWithin(Duration.ofSeconds(3));

        // 4. 验证新请求被拒绝（503 或连接拒绝）
        verifyNewRequestRejected();

        // 5. 等待慢请求完成
        slowRequestFuture.get(SLOW_UPSTREAM_DELAY_MS + 5000,
                TimeUnit.MILLISECONDS);

        // 6. 验证在途请求成功完成
        assertThat(slowError.get())
                .as("在途请求不应报错")
                .isNull();
        assertThat(slowResponse.get()).isNotNull();
        assertThat(slowResponse.get().statusCode())
                .as("在途请求应返回 200")
                .isEqualTo(200);
        assertThat(slowResponse.get().body())
                .isEqualTo("slow-response");

        // 7. 等待停机完成
        shutdownFuture.get(10, TimeUnit.SECONDS);
        assertThat(shutdownCoordinator.isRunning()).isFalse();
        assertThat(inFlightTracker.getInFlightCount()).isZero();
    }

    /**
     * 验证停机期间已有连接上发送 /health/ready 返回 503 + draining。
     *
     * <p>关键：serverChannel 关闭后无法建立新 TCP 连接，所以必须在停机前
     * 通过 raw socket 建立 keep-alive 连接，停机后复用该连接发请求。
     *
     * Requirements: 7.3
     */
    @Test
    void readinessReturnsDrainingDuringShutdown() throws Exception {
        // 1. 停机前建立 TCP 连接（keep-alive），后续复用
        Socket preEstablished = new Socket();
        preEstablished.connect(
                new InetSocketAddress("localhost", gatewayPort), 3000);
        preEstablished.setSoTimeout(5000);

        // 先发一个请求确认连接可用
        sendRawRequest(preEstablished, "/health/ready");
        String warmupResp = readResponse(preEstablished);
        assertThat(warmupResp).contains("200");

        // 2. 设置 upstream 延迟，让停机期间有在途请求
        CountDownLatch upstreamReceived = new CountDownLatch(1);
        upstreamServer.setHandler(req -> {
            upstreamReceived.countDown();
            try {
                Thread.sleep(SLOW_UPSTREAM_DELAY_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            ByteBuf content = Unpooled.copiedBuffer("ok", CharsetUtil.UTF_8);
            FullHttpResponse resp = new DefaultFullHttpResponse(
                    HttpVersion.HTTP_1_1, HttpResponseStatus.OK, content);
            resp.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain");
            resp.headers().setInt(HttpHeaderNames.CONTENT_LENGTH,
                    content.readableBytes());
            return resp;
        });

        // 发送慢请求保持在途
        CompletableFuture.runAsync(() -> {
            try {
                httpClient.send(
                        HttpRequest.newBuilder()
                                .uri(gatewayUri("/api/test/hold"))
                                .GET().build(),
                        HttpResponse.BodyHandlers.ofString());
            } catch (Exception ignored) {
                // 停机期间可能连接被关闭
            }
        });

        assertThat(upstreamReceived.await(5, TimeUnit.SECONDS)).isTrue();

        // 3. 触发停机
        CompletableFuture<Void> shutdownFuture =
                CompletableFuture.runAsync(() -> shutdownCoordinator.stop());

        assertDrainActivatedWithin(Duration.ofSeconds(3));

        // 4. 用停机前建立的连接发 /health/ready，验证返回 503 + draining
        // 健康检查端点在 drain 期间被 DrainHandler 放行到 RoutingHandler
        try {
            sendRawRequest(preEstablished, "/health/ready");
            String response = readResponse(preEstablished);
            assertThat(response)
                    .as("/health/ready 应返回 503")
                    .contains("503");
            assertThat(response)
                    .as("响应体应包含 draining")
                    .contains("draining");
        } finally {
            preEstablished.close();
        }

        shutdownFuture.get(15, TimeUnit.SECONDS);
    }

    /**
     * 验证无在途请求时停机立即完成。
     * Requirements: 3.5
     */
    @Test
    void shutdownCompletesImmediatelyWhenNoInFlightRequests()
            throws Exception {
        assertThat(inFlightTracker.getInFlightCount()).isZero();

        Instant before = Instant.now();
        shutdownCoordinator.stop();
        Duration elapsed = Duration.between(before, Instant.now());

        assertThat(shutdownCoordinator.isRunning()).isFalse();
        // 无在途请求时应在 5 秒内完成（workerGroup.shutdownGracefully 默认
        // 有 2 秒 quiet period，加上连接池关闭开销，远小于 10 秒超时）
        assertThat(elapsed).isLessThan(Duration.ofSeconds(5));
    }

    private URI gatewayUri(String path) {
        return URI.create("http://localhost:" + gatewayPort + path);
    }

    /**
     * 轮询等待 drain 状态激活。
     */
    private void assertDrainActivatedWithin(Duration timeout)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!drainHandler.isDraining()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError(
                        "排空未在 " + timeout + " 内激活");
            }
            Thread.sleep(50);
        }
    }

    /**
     * 验证新请求被拒绝：尝试通过已有连接发送请求，
     * 期望收到 503 或连接被拒绝。
     */
    private void verifyNewRequestRejected() {
        try (Socket socket = new Socket()) {
            socket.connect(
                    new InetSocketAddress("localhost", gatewayPort), 1000);
            socket.setSoTimeout(3000);
            // 如果连接成功（已有连接），发送请求应收到 503
            var out = socket.getOutputStream();
            String raw = "GET /api/test/new HTTP/1.1\r\n"
                    + "Host: localhost:" + gatewayPort + "\r\n"
                    + "Connection: close\r\n"
                    + "\r\n";
            out.write(raw.getBytes(StandardCharsets.UTF_8));
            out.flush();

            String response = readResponse(socket);
            assertThat(response)
                    .as("新请求应收到 503")
                    .contains("503");
        } catch (IOException e) {
            // 连接被拒绝也是预期行为（serverChannel 已关闭）
            assertThat(e.getMessage())
                    .as("连接应被拒绝")
                    .containsAnyOf("Connection refused", "refused",
                            "reset", "closed");
        }
    }

    /**
     * 通过已有 socket 发送 HTTP GET 请求（keep-alive）。
     */
    private void sendRawRequest(Socket socket, String path)
            throws IOException {
        var out = socket.getOutputStream();
        String raw = "GET " + path + " HTTP/1.1\r\n"
                + "Host: localhost:" + gatewayPort + "\r\n"
                + "Connection: keep-alive\r\n"
                + "\r\n";
        out.write(raw.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private static String readResponse(Socket socket) throws IOException {
        byte[] buf = new byte[4096];
        StringBuilder sb = new StringBuilder();
        var in = socket.getInputStream();
        int read;
        while ((read = in.read(buf)) != -1) {
            sb.append(new String(buf, 0, read, StandardCharsets.UTF_8));
            if (sb.toString().contains("\r\n\r\n")) {
                // 读取 body
                String headers = sb.toString();
                int clIdx = headers.toLowerCase(java.util.Locale.ROOT)
                        .indexOf("content-length:");
                if (clIdx >= 0) {
                    int nlIdx = headers.indexOf("\r\n", clIdx);
                    int cl = Integer.parseInt(
                            headers.substring(clIdx + 15, nlIdx).trim());
                    int bodyStart = headers.indexOf("\r\n\r\n") + 4;
                    int bodyRead = headers.length() - bodyStart;
                    while (bodyRead < cl) {
                        read = in.read(buf);
                        if (read == -1) {
                            break;
                        }
                        sb.append(new String(buf, 0, read,
                                StandardCharsets.UTF_8));
                        bodyRead += read;
                    }
                }
                break;
            }
        }
        return sb.toString();
    }

    private static int findAvailablePort() throws IOException {
        try (java.net.ServerSocket ss = new java.net.ServerSocket(0)) {
            return ss.getLocalPort();
        }
    }
}
