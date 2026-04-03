package com.lei.gateway.observability;

import com.lei.gateway.config.ObservabilityProperties;
import com.lei.gateway.pool.ConcurrentPool;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufAllocatorMetric;
import io.netty.buffer.ByteBufAllocatorMetricProvider;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SingleThreadEventLoop;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/**
 * 指标采集器，封装 Micrometer MeterRegistry 的指标注册和记录。
 *
 * <p>指标设计参考 RED method（Rate、Errors、Duration）。
 * 当 {@code metricsEnabled=false} 时所有 record 方法为空操作。
 */
@Component
public class MetricsCollector {

    private final MeterRegistry meterRegistry;
    private final ObservabilityProperties config;

    /** 创建指标采集器。 */
    public MetricsCollector(MeterRegistry meterRegistry, ObservabilityProperties config) {
        this.meterRegistry = meterRegistry;
        this.config = config;
    }

    /**
     * 记录一次代理请求的指标。
     *
     * @param method        HTTP 方法
     * @param path          请求路径
     * @param statusCode    HTTP 状态码
     * @param durationNanos 响应耗时（纳秒）
     */
    public void recordRequest(String method, String path, int statusCode,
            long durationNanos) {
        if (!config.isMetricsEnabled()) {
            return;
        }

        Counter.builder("gateway.requests.total")
                .tag("method", method)
                .tag("path", path)
                .tag("status", String.valueOf(statusCode))
                .register(meterRegistry)
                .increment();

        Timer.builder("gateway.requests.duration")
                .tag("method", method)
                .tag("path", path)
                .register(meterRegistry)
                .record(durationNanos, TimeUnit.NANOSECONDS);

        String statusClass = statusCode >= 500 ? "5xx" : (statusCode >= 400 ? "4xx" : null);
        if (statusClass != null) {
            Counter.builder("gateway.requests.errors")
                    .tag("status_class", statusClass)
                    .register(meterRegistry)
                    .increment();
        }
    }

    /**
     * 记录一次 upstream 连接建立的耗时。
     *
     * @param upstream      upstream 地址标识
     * @param durationNanos 连接建立耗时（纳秒）
     * @param success       连接是否成功
     * @param slowThresholdMillis 慢连接阈值（毫秒）
     */
    public void recordUpstreamConnect(String upstream, long durationNanos,
            boolean success, int slowThresholdMillis) {
        if (!config.isMetricsEnabled()) {
            return;
        }

        if (!success) {
            Counter.builder("gateway.upstream.connect.failures")
                    .tag("upstream", upstream)
                    .register(meterRegistry)
                    .increment();
        }

        long durationMillis = TimeUnit.NANOSECONDS.toMillis(durationNanos);
        if (durationMillis > slowThresholdMillis) {
            Counter.builder("gateway.upstream.connect.slow")
                    .tag("upstream", upstream)
                    .register(meterRegistry)
                    .increment();
            Timer.builder("gateway.upstream.connect.duration")
                    .tag("upstream", upstream)
                    .register(meterRegistry)
                    .record(durationNanos, TimeUnit.NANOSECONDS);
        }
    }

    /**
     * 记录一次连接池借用失败。
     *
     * @param upstream upstream 地址标识
     */
    public void recordPoolBorrowFailure(String upstream) {
        if (!config.isMetricsEnabled()) {
            return;
        }

        Counter.builder("gateway.pool.borrow.failures")
                .tag("upstream", upstream)
                .register(meterRegistry)
                .increment();
    }

    /**
     * 记录插件执行耗时。
     */
    public void recordPluginDuration(String pluginName, String routeId,
            long durationNanos) {
        if (!config.isMetricsEnabled()) {
            return;
        }
        Timer.builder("gateway.plugin.duration")
                .tag("plugin", pluginName)
                .tag("routeId", routeId == null ? "unknown" : routeId)
                .register(meterRegistry)
                .record(durationNanos, TimeUnit.NANOSECONDS);
    }

    /**
     * 记录插件执行决策。
     */
    public void recordPluginDecision(String pluginName, String decision,
            String routeId) {
        if (!config.isMetricsEnabled()) {
            return;
        }
        Counter.builder("gateway.plugin.decisions")
                .tag("plugin", pluginName)
                .tag("decision", decision)
                .tag("routeId", routeId == null ? "unknown" : routeId)
                .register(meterRegistry)
                .increment();
    }

    /**
     * 记录安全过滤决策计数。
     */
    public void recordSecurityFilterDecision(String filter, String decision,
            String reason, String routeId) {
        if (!config.isMetricsEnabled()) {
            return;
        }
        Counter.builder("gateway.security.filter.decisions")
                .tag("filter", filter)
                .tag("decision", decision)
                .tag("reason", reason == null ? "none" : reason)
                .tag("routeId", routeId == null ? "unknown" : routeId)
                .register(meterRegistry)
                .increment();
    }

    /**
     * 记录安全过滤耗时。
     */
    public void recordSecurityFilterDuration(String filter, String routeId,
            long durationNanos) {
        if (!config.isMetricsEnabled()) {
            return;
        }
        Timer.builder("gateway.security.filter.duration")
                .tag("filter", filter)
                .tag("routeId", routeId == null ? "unknown" : routeId)
                .register(meterRegistry)
                .record(durationNanos, TimeUnit.NANOSECONDS);
    }

    /**
     * 记录认证失败。
     */
    public void recordAuthFailure(String reason) {
        if (!config.isMetricsEnabled()) {
            return;
        }
        Counter.builder("gateway.security.auth.failures")
                .tag("reason", reason == null ? "unknown" : reason)
                .register(meterRegistry)
                .increment();
    }

    /**
     * 记录限流命中。
     */
    public void recordRateLimitHit(String stage, String routeId) {
        if (!config.isMetricsEnabled()) {
            return;
        }
        Counter.builder("gateway.security.rate_limit.hits")
                .tag("stage", stage)
                .tag("routeId", routeId == null ? "unknown" : routeId)
                .register(meterRegistry)
                .increment();
    }

    /**
     * 记录代理链路失败事件。
     *
     * @param stage 失败阶段（如 acquire/h2_error/request）
     * @param reason 失败原因（如 max_streams_exhausted/connect_timeout）
     */
    public void recordProxyFailure(String stage, String reason) {
        if (!config.isMetricsEnabled()) {
            return;
        }
        Counter.builder("gateway.proxy.failures")
                .tag("stage", normalizeTag(stage))
                .tag("reason", normalizeTag(reason))
                .register(meterRegistry)
                .increment();
    }

    /**
     * 记录代理错误响应及连接处理策略（keep-alive/close）。
     *
     * @param statusCode HTTP 状态码
     * @param connectionPolicy 连接策略：keep_alive 或 close
     */
    public void recordProxyErrorResponse(int statusCode, String connectionPolicy) {
        if (!config.isMetricsEnabled()) {
            return;
        }
        Counter.builder("gateway.proxy.error.responses")
                .tag("status", String.valueOf(statusCode))
                .tag("connection_policy", normalizeTag(connectionPolicy))
                .register(meterRegistry)
                .increment();
    }

    /**
     * 记录安全组件降级/回退事件。
     */
    public void recordSecurityFallback(String component, String reason) {
        if (!config.isMetricsEnabled()) {
            return;
        }
        Counter.builder("gateway.security.fallbacks")
                .tag("component", component)
                .tag("reason", reason == null ? "unknown" : reason)
                .register(meterRegistry)
                .increment();
    }

    /**
     * 注册活跃连接数 gauge。
     *
     * @param activeConnections 活跃连接数原子计数器
     */
    public void registerActiveConnections(AtomicInteger activeConnections) {
        if (!config.isMetricsEnabled()) {
            return;
        }

        meterRegistry.gauge("gateway.connections.active", activeConnections,
                AtomicInteger::get);
    }

    /**
     * 注册连接池指标 gauge（每个 host:port 一组）。
     *
     * @param hostPort upstream 的 host:port 标识
     * @param pool     对应的连接池实例
     */
    public void registerPoolMetrics(String hostPort, ConcurrentPool<?> pool) {
        if (!config.isMetricsEnabled()) {
            return;
        }

        meterRegistry.gauge("gateway.pool.active", Tags.of(
                "upstream", hostPort), pool, ConcurrentPool::getActiveCount);
        meterRegistry.gauge("gateway.pool.idle", Tags.of(
                "upstream", hostPort), pool, ConcurrentPool::getIdleCount);
        meterRegistry.gauge("gateway.pool.total", Tags.of(
                "upstream", hostPort), pool, ConcurrentPool::getTotalCount);
    }

    /** 注册 JVM 指标（JvmMemoryMetrics、JvmGcMetrics、JvmThreadMetrics）。 */
    @SuppressWarnings("resource")
    public void registerJvmMetrics() {
        if (!config.isMetricsEnabled()) {
            return;
        }

        new JvmMemoryMetrics().bindTo(meterRegistry);
        // JvmGcMetrics 实现了 AutoCloseable，但其生命周期与应用一致，无需手动关闭
        new JvmGcMetrics().bindTo(meterRegistry);
        new JvmThreadMetrics().bindTo(meterRegistry);
    }

    /**
     * 注册 Netty 运行时指标。
     *
     * @param workerGroup Netty worker EventLoopGroup
     * @param allocator   ByteBuf allocator
     */
    public void registerNettyMetrics(EventLoopGroup workerGroup, ByteBufAllocator allocator) {
        if (!config.isMetricsEnabled()) {
            return;
        }

        meterRegistry.gauge("netty.eventloop.pending.tasks", workerGroup, group -> {
            int total = 0;
            for (var eventLoop : group) {
                if (eventLoop instanceof SingleThreadEventLoop singleLoop) {
                    total += singleLoop.pendingTasks();
                }
            }
            return total;
        });

        if (allocator instanceof ByteBufAllocatorMetricProvider metricProvider) {
            ByteBufAllocatorMetric metric = metricProvider.metric();
            meterRegistry.gauge("netty.allocator.used.direct.memory", metric,
                    ByteBufAllocatorMetric::usedDirectMemory);
            meterRegistry.gauge("netty.allocator.used.heap.memory", metric,
                    ByteBufAllocatorMetric::usedHeapMemory);
        }
    }

    /**
     * 输出 Prometheus 格式文本（供 /metrics 端点使用）。
     * 非 PrometheusMeterRegistry 时返回提示信息。
     *
     * @return Prometheus 格式的指标文本，或不支持时的提示
     */
    public String scrape() {
        if (meterRegistry instanceof PrometheusMeterRegistry prometheusRegistry) {
            return prometheusRegistry.scrape();
        }
        return "# Prometheus scrape not supported for current MeterRegistry: "
                + meterRegistry.getClass().getSimpleName();
    }

    private static String normalizeTag(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        return value.toLowerCase(Locale.ROOT).replace(' ', '_');
    }
}
