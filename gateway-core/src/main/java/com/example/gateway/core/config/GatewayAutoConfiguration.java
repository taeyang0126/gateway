package com.example.gateway.core.config;

import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 启用网关配置属性绑定，注册共享 Bean。
 */
@Configuration
@EnableConfigurationProperties({
    GatewayProperties.class,
    RequestLimitProperties.class,
    ConnectionPoolProperties.class,
    ObservabilityProperties.class
})
public class GatewayAutoConfiguration {

    /** 创建 worker EventLoopGroup Bean，供 NettyServerBootstrap 和 UpstreamConnectionPool 共享。 */
    @Bean(destroyMethod = "shutdownGracefully")
    public io.netty.channel.EventLoopGroup workerGroup() {
        return new io.netty.channel.nio.NioEventLoopGroup();
    }

    /**
     * 创建 PrometheusMeterRegistry Bean。
     * 仅在没有其他 MeterRegistry Bean 时生效（兼容引入了 actuator 自动配置的场景）。
     */
    @Bean
    @ConditionalOnMissingBean
    public PrometheusMeterRegistry meterRegistry() {
        return new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
    }
}
