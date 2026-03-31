package com.lei.gateway.proxy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lei.gateway.config.GatewayProperties;
import com.lei.gateway.config.HealthProperties;
import com.lei.gateway.config.Route;
import com.lei.gateway.config.RouteResolver;
import io.netty.channel.Channel;
import io.netty.util.HashedWheelTimer;
import io.netty.util.Timeout;
import java.net.URI;
import java.util.LinkedHashMap;
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

    /** 每 256 次循环检查一次预算到期，兼顾开销与响应速度。 */
    private static final int BUDGET_CHECK_MASK = 0xFF;

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

        long deadlineNanos =
                System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        AtomicBoolean cancelled = new AtomicBoolean(false);
        HashedWheelTimer timer = new HashedWheelTimer();
        Timeout timeout = timer.newTimeout(t -> {
            cancelled.set(true);
            LOG.warn("预热总超时已到达（{} 秒），取消剩余预热", timeoutSeconds);
        }, timeoutSeconds, TimeUnit.SECONDS);

        try {
            warmupInternalHotPaths(cancelled, deadlineNanos);

            if (!isWarmupBudgetExpired(cancelled, deadlineNanos)) {
                warmupUpstreams(cancelled, deadlineNanos);
            }

            if (isWarmupBudgetExpired(cancelled, deadlineNanos)) {
                LOG.warn("预热在预算内未完成，已跳过剩余项");
            } else {
                LOG.info("预热完成");
            }
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
    private void warmupInternalHotPaths(AtomicBoolean cancelled, long deadlineNanos) {
        List<Route> routes = gatewayProperties.getRoutes();
        if (routes.isEmpty()) {
            LOG.info("无已配置路由，跳过内部热路径预热");
            return;
        }

        int iterationsPerRoute = computeInternalIterationsPerRoute(routes.size());
        long totalIterations = (long) routes.size() * iterationsPerRoute;
        LOG.info(
                "阶段1：内部热路径预热，路由数 {}，单路由迭代 {} 次，总迭代约 {} 次",
                routes.size(), iterationsPerRoute, totalIterations);

        for (Route route : routes) {
            if (isWarmupBudgetExpired(cancelled, deadlineNanos)) {
                LOG.warn("内部热路径预热提前结束：预算已耗尽");
                return;
            }
            String probePath = stripWildcard(route.getPathPrefix());
            for (int i = 0; i < iterationsPerRoute; i++) {
                if ((i & BUDGET_CHECK_MASK) == 0
                        && isWarmupBudgetExpired(cancelled, deadlineNanos)) {
                    LOG.warn("内部热路径预热提前结束：预算已耗尽");
                    return;
                }
                routeResolver.resolve(probePath);
            }
        }

        if (isWarmupBudgetExpired(cancelled, deadlineNanos)) {
            LOG.warn("JSON 预热前预算已耗尽，跳过 JSON 预热");
            return;
        }
        warmupJsonCodec();
    }

    /**
     * 阶段2：upstream 预热请求。
     * 按 host:port 去重后，对每个 upstream 主动 acquire/release 若干次，预热连接池创建路径。
     */
    private void warmupUpstreams(AtomicBoolean cancelled, long deadlineNanos) {
        List<Route> routes = gatewayProperties.getRoutes();
        if (routes.isEmpty()) {
            return;
        }

        Map<String, UpstreamTarget> targets = collectDistinctTargets(routes);
        int acquireCount = healthProperties.getWarmupUpstreamAcquireCount();
        LOG.info("阶段2：upstream 预热，路由数 {}，去重后 upstream 数 {}，每个预热 {} 次",
                routes.size(), targets.size(), acquireCount);

        for (UpstreamTarget target : targets.values()) {
            if (isWarmupBudgetExpired(cancelled, deadlineNanos)) {
                LOG.warn("预热超时，放弃剩余 upstream 预热");
                return;
            }
            warmupSingleUpstream(target, cancelled, deadlineNanos, acquireCount);
        }
    }

    private Map<String, UpstreamTarget> collectDistinctTargets(List<Route> routes) {
        Map<String, UpstreamTarget> targets = new LinkedHashMap<>();
        for (Route route : routes) {
            UpstreamTarget target = parseTarget(route);
            if (target == null) {
                continue;
            }
            String key = target.host() + ":" + target.port();
            targets.putIfAbsent(key, target);
        }
        return targets;
    }

    private UpstreamTarget parseTarget(Route route) {
        String upstream = route.getUpstream();
        URI uri;
        try {
            uri = URI.create(upstream);
        } catch (IllegalArgumentException e) {
            LOG.warn("无效的 upstream 地址，跳过预热: routeId={} upstream={}",
                    route.getId(), upstream, e);
            return null;
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            LOG.warn("upstream 缺少 host，跳过预热: routeId={} upstream={}",
                    route.getId(), upstream);
            return null;
        }
        int port = resolvePort(uri);
        return new UpstreamTarget(host, port);
    }

    private int resolvePort(URI uri) {
        if (uri.getPort() > 0) {
            return uri.getPort();
        }
        if ("https".equalsIgnoreCase(uri.getScheme())) {
            return 443;
        }
        return 80;
    }

    private void warmupSingleUpstream(UpstreamTarget target, AtomicBoolean cancelled,
            long deadlineNanos, int acquireCount) {
        for (int i = 0; i < acquireCount; i++) {
            if (isWarmupBudgetExpired(cancelled, deadlineNanos)) {
                LOG.warn("upstream 预热提前结束（预算耗尽）: {}:{}",
                        target.host(), target.port());
                return;
            }

            long remainingNanos = deadlineNanos - System.nanoTime();
            long timeoutMillis =
                    Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remainingNanos));
            try {
                CompletableFuture<Channel> future =
                        connectionPool.acquire(target.host(), target.port());
                Channel channel = future.get(timeoutMillis, TimeUnit.MILLISECONDS);
                connectionPool.release(channel);
            } catch (TimeoutException e) {
                LOG.warn("upstream 预热超时: {}:{}（剩余预算 {} ms）",
                        target.host(), target.port(), timeoutMillis);
                return;
            } catch (Exception e) {
                LOG.warn("upstream 预热失败: {}:{}", target.host(), target.port(), e);
                return;
            }
        }
        LOG.info("upstream 预热成功: {}:{}（{} 次）",
                target.host(), target.port(), acquireCount);
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

    private boolean isWarmupBudgetExpired(AtomicBoolean cancelled,
            long deadlineNanos) {
        if (cancelled.get()) {
            return true;
        }
        if (System.nanoTime() < deadlineNanos) {
            return false;
        }
        cancelled.set(true);
        return true;
    }

    private int computeInternalIterationsPerRoute(int routeCount) {
        int baseIterationsPerRoute =
                healthProperties.getWarmupInternalBaseIterationsPerRoute();
        int maxTotalIterations =
                healthProperties.getWarmupInternalMaxTotalIterations();
        int minIterationsPerRoute =
                healthProperties.getWarmupInternalMinIterationsPerRoute();

        long desiredTotal =
                Math.min((long) routeCount * baseIterationsPerRoute,
                        (long) maxTotalIterations);
        long averagedPerRoute =
                (desiredTotal + routeCount - 1L) / routeCount;
        int computedPerRoute = (int) Math.max(1L, averagedPerRoute);
        return Math.max(computedPerRoute, minIterationsPerRoute);
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

    private record UpstreamTarget(String host, int port) {
    }
}
