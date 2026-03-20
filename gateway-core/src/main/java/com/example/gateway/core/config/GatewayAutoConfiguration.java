package com.example.gateway.core.config;

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
}
