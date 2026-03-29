package com.lei.gateway.core.config;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 健康检查配置，绑定 {@code gateway.health} 前缀。
 */
@Validated
@ConfigurationProperties(prefix = "gateway.health")
public class HealthProperties {

    /**
     * bind 端口之后，readiness 探针返回 DOWN 的额外等待秒数，默认 0（不延迟）。
     *
     * <p>用于 JIT 编译稳定等场景：端口已 bind 可接收连接，但在此期间
     * {@code /health/ready} 返回 503，K8s 不会将流量导入。
     * 超过该时间后 readiness 才返回 UP。
     */
    @Min(0)
    private int startupDelaySeconds = 0;

    /**
     * bind 端口之前，{@code WarmupRunner} 预热操作的最长超时秒数，默认 10。
     *
     * <p>预热包括内部热路径（路由匹配、JSON 序列化）和 upstream 预热请求。
     * 超时后放弃剩余预热，直接 bind 端口，不阻塞启动。
     */
    @Min(1)
    private int warmupTimeoutSeconds = 10;

    /**
     * 内部热路径预热时，每条路由的基准迭代次数。
     *
     * <p>最终单路由迭代会结合 {@code warmupInternalMaxTotalIterations}
     * 和 {@code warmupInternalMinIterationsPerRoute} 计算，避免路由规模增大后
     * 启动时间呈线性放大。
     */
    @Min(1)
    private int warmupInternalBaseIterationsPerRoute = 100000;

    /**
     * 内部热路径预热的总迭代上限。
     *
     * <p>用于控制大路由表场景下的预热成本，避免 O(N^2) 级放大。
     */
    @Min(1)
    private int warmupInternalMaxTotalIterations = 1000000;

    /**
     * 内部热路径预热时每条路由的最小迭代次数。
     *
     * <p>用于保证路由规模较大时仍有最低 JIT 触发热度。
     */
    @Min(1)
    private int warmupInternalMinIterationsPerRoute = 10000;

    /**
     * 每个 upstream 在启动预热阶段主动 acquire/release 的次数。
     */
    @Min(1)
    private int warmupUpstreamAcquireCount = 1;

    public int getStartupDelaySeconds() {
        return startupDelaySeconds;
    }

    public void setStartupDelaySeconds(int startupDelaySeconds) {
        this.startupDelaySeconds = startupDelaySeconds;
    }

    public int getWarmupTimeoutSeconds() {
        return warmupTimeoutSeconds;
    }

    public void setWarmupTimeoutSeconds(int warmupTimeoutSeconds) {
        this.warmupTimeoutSeconds = warmupTimeoutSeconds;
    }

    public int getWarmupInternalBaseIterationsPerRoute() {
        return warmupInternalBaseIterationsPerRoute;
    }

    public void setWarmupInternalBaseIterationsPerRoute(
            int warmupInternalBaseIterationsPerRoute) {
        this.warmupInternalBaseIterationsPerRoute =
                warmupInternalBaseIterationsPerRoute;
    }

    public int getWarmupInternalMaxTotalIterations() {
        return warmupInternalMaxTotalIterations;
    }

    public void setWarmupInternalMaxTotalIterations(
            int warmupInternalMaxTotalIterations) {
        this.warmupInternalMaxTotalIterations = warmupInternalMaxTotalIterations;
    }

    public int getWarmupInternalMinIterationsPerRoute() {
        return warmupInternalMinIterationsPerRoute;
    }

    public void setWarmupInternalMinIterationsPerRoute(
            int warmupInternalMinIterationsPerRoute) {
        this.warmupInternalMinIterationsPerRoute =
                warmupInternalMinIterationsPerRoute;
    }

    public int getWarmupUpstreamAcquireCount() {
        return warmupUpstreamAcquireCount;
    }

    public void setWarmupUpstreamAcquireCount(int warmupUpstreamAcquireCount) {
        this.warmupUpstreamAcquireCount = warmupUpstreamAcquireCount;
    }
}
