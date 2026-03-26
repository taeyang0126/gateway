package com.lei.gateway.core.proxy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lei.gateway.core.config.GatewayProperties;
import com.lei.gateway.core.config.HealthProperties;
import com.lei.gateway.core.config.Route;
import com.lei.gateway.core.config.RouteResolver;
import io.netty.channel.Channel;
import io.netty.util.HashedWheelTimer;
import io.netty.util.Timeout;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 启动预热执行器，在 bind 端口前执行两阶段预热。
 *
 * <p>阶段1：内部热路径预热 — 模拟路由匹配、JSON 序列化触发 JIT 编译。
 *
 * <p>阶段2：upstream 预热请求 — 向已配置的 upstream 发请求，预热连接池。
 *
 * <p>整体受 {@code warmupTimeoutSeconds} 总超时控制（通过 Netty
 * {@link HashedWheelTimer} 精确调度），超时后放弃剩余预热，不阻塞启动。
 */
public class WarmupRunner {

    private static final Logger LOG = LoggerFactory.getLogger(WarmupRunner.class);

    /** 内部热路径预热迭代次数，需足够多以触发 C2 JIT 编译。 */
    private static final int INTERNAL_WARMUP_ITERATIONS = 1_000_000;

    private final RouteResolver routeResolver;
    private final GatewayProperties gatewayProperties;
    private final UpstreamConnectionPool connectionPool;
    private final HealthProperties healthProperties;
    private final ObjectMapper objectMapper;

    /** 创建 WarmupRunner。 */
    public WarmupRunner(RouteResolver routeResolver,
            GatewayProperties gatewayProperties,
            UpstreamConnectionPool connectionPool,
            HealthProperties healthProperties,
            ObjectMapper objectMapper) {
        this.routeResolver = routeResolver;
        this.gatewayProperties = gatewayProperties;
        this.connectionPool = connectionPool;
        this.healthProperties = healthProperties;
        this.objectMapper = objectMapper;
    }

    /**
     * 执行预热，受 {@code warmupTimeoutSeconds} 总超时控制。
     *
     * <p>使用 {@link HashedWheelTimer} 精确调度超时，到期后设置取消标志，
     * 各阶段在循环中检查该标志以快速退出。预热失败不阻塞启动。
     */
    public void runWarmup() {
        int timeoutSeconds = healthProperties.getWarmupTimeoutSeconds();
        LOG.info("开始预热，总超时 {} 秒", timeoutSeconds);

        AtomicBoolean cancelled = new AtomicBoolean(false);
        HashedWheelTimer timer = new HashedWheelTimer();
        Timeout timeout = timer.newTimeout(t -> {
            cancelled.set(true);
            LOG.warn("预热总超时已到达（{} 秒），取消剩余预热", timeoutSeconds);
        }, timeoutSeconds, TimeUnit.SECONDS);

        try {
            warmupInternalHotPaths(cancelled);

            if (!cancelled.get()) {
                warmupUpstreams(cancelled, timeoutSeconds);
            }

            LOG.info("预热完成");
        } catch (Exception e) {
            LOG.warn("预热过程异常，继续启动", e);
        } finally {
            timeout.cancel();
            timer.stop();
        }
    }

    /**
     * 阶段1：内部热路径预热。
     * 遍历已配置路由，对每个路由的 pathPrefix 执行多次 resolve，触发 JIT。
     * 同时执行模拟 JSON 序列化/反序列化触发 Jackson 类加载。
     */
    private void warmupInternalHotPaths(AtomicBoolean cancelled) {
        List<Route> routes = gatewayProperties.getRoutes();
        if (routes.isEmpty()) {
            LOG.info("无已配置路由，跳过内部热路径预热");
            return;
        }

        LOG.info("阶段1：内部热路径预热，路由数 {}，迭代 {} 次",
                routes.size(), INTERNAL_WARMUP_ITERATIONS);
        for (Route route : routes) {
            if (cancelled.get()) {
                return;
            }
            String probePath = stripWildcard(route.getPathPrefix());
            for (int i = 0; i < INTERNAL_WARMUP_ITERATIONS; i++) {
                routeResolver.resolve(probePath);
            }
        }

        warmupJsonCodec();
    }

    /**
     * 阶段2：upstream 预热请求。
     * 向每个已配置的 upstream acquire 一次连接，预热连接池创建路径。
     */
    private void warmupUpstreams(AtomicBoolean cancelled, int timeoutSeconds) {
        List<Route> routes = gatewayProperties.getRoutes();
        if (routes.isEmpty()) {
            return;
        }

        LOG.info("阶段2：upstream 预热请求，路由数 {}", routes.size());
        for (Route route : routes) {
            if (cancelled.get()) {
                LOG.warn("预热超时，放弃剩余 upstream 预热");
                return;
            }
            warmupSingleUpstream(route, timeoutSeconds);
        }
    }

    private void warmupSingleUpstream(Route route, int timeoutSeconds) {
        String upstream = route.getUpstream();
        URI uri;
        try {
            uri = URI.create(upstream);
        } catch (IllegalArgumentException e) {
            LOG.warn("无效的 upstream 地址，跳过预热: {}", upstream, e);
            return;
        }

        String host = uri.getHost();
        int port = uri.getPort() > 0 ? uri.getPort() : 80;

        try {
            CompletableFuture<Channel> future = connectionPool.acquire(host, port);
            Channel channel = future.get(timeoutSeconds, TimeUnit.SECONDS);
            connectionPool.release(channel);
            LOG.info("upstream 预热成功: {}:{}", host, port);
        } catch (TimeoutException e) {
            LOG.warn("upstream 预热超时: {}:{}", host, port);
        } catch (Exception e) {
            LOG.warn("upstream 预热失败: {}:{}", host, port, e);
        }
    }

    private void warmupJsonCodec() {
        try {
            String json = "{\"status\":\"UP\",\"warmup\":true}";
            objectMapper.readTree(json);
            objectMapper.writeValueAsString(Map.of("warmup", true));
        } catch (Exception e) {
            LOG.warn("JSON 预热失败", e);
        }
    }

    /**
     * 去掉 Ant 风格通配符部分，保留纯路径前缀。
     * 例如 "/api/**" → "/api/", "/api/*" → "/api/"
     */
    static String stripWildcard(String pathPrefix) {
        int starIdx = pathPrefix.indexOf('*');
        if (starIdx < 0) {
            return pathPrefix;
        }
        int lastSlash = pathPrefix.lastIndexOf('/', starIdx);
        if (lastSlash >= 0) {
            return pathPrefix.substring(0, lastSlash + 1);
        }
        return pathPrefix.substring(0, starIdx);
    }
}
