package com.lei.gateway.core.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
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
     * 默认创建 PrometheusMeterRegistry。
     * 仅在没有其他 MeterRegistry Bean 时生效（兼容引入了 actuator 自动配置的场景）。
     */
    @Bean
    @ConditionalOnMissingBean(MeterRegistry.class)
    public MeterRegistry meterRegistry() {
        return new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
    }

    /**
     * 共享 ObjectMapper Bean，供 AccessLogWriter 等组件统一使用。
     * 仅在没有其他 ObjectMapper Bean 时生效。
     */
    @Bean
    @ConditionalOnMissingBean(ObjectMapper.class)
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }
}
