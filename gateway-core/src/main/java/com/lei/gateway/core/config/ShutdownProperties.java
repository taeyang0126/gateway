package com.lei.gateway.core.config;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 优雅停机配置，绑定 {@code gateway.shutdown} 前缀。
 */
@Validated
@ConfigurationProperties(prefix = "gateway.shutdown")
public class ShutdownProperties {

    /** 优雅停机最大等待时间（秒），默认 30。 */
    @Min(1)
    private int shutdownTimeoutSeconds = 30;

    /** 等待在途请求完成时的轮询间隔（毫秒），默认 500。 */
    private int shutdownPollIntervalMillis = 500;

    public int getShutdownTimeoutSeconds() {
        return shutdownTimeoutSeconds;
    }

    public void setShutdownTimeoutSeconds(int shutdownTimeoutSeconds) {
        this.shutdownTimeoutSeconds = shutdownTimeoutSeconds;
    }

    public int getShutdownPollIntervalMillis() {
        return shutdownPollIntervalMillis;
    }

    public void setShutdownPollIntervalMillis(int shutdownPollIntervalMillis) {
        this.shutdownPollIntervalMillis = shutdownPollIntervalMillis;
    }
}
