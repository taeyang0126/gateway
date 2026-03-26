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
}
