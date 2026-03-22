package com.lei.gateway.core.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lei.gateway.core.filter.Filter;
import com.lei.gateway.core.filter.FilterChainFactory;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    ObservabilityProperties.class,
    FilterProperties.class
})
public class GatewayAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(GatewayAutoConfiguration.class);

    private final GatewayProperties gatewayProperties;
    private final FilterProperties filterProperties;

    /** 创建 GatewayAutoConfiguration。 */
    public GatewayAutoConfiguration(GatewayProperties gatewayProperties,
            FilterProperties filterProperties) {
        this.gatewayProperties = gatewayProperties;
        this.filterProperties = filterProperties;
    }

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

    /**
     * 注册 FilterChainFactory Bean。
     * filters 列表由 Spring 自动注入所有 Filter 类型的 Bean（各过滤器任务中注册）。
     * 当容器中没有任何 Filter Bean 时，传入空列表。
     */
    @Bean
    public FilterChainFactory filterChainFactory(
            @org.springframework.beans.factory.annotation.Autowired(required = false)
            List<Filter> filters) {
        Map<String, Filter> registry = filters == null ? Map.of()
                : filters.stream().collect(
                        Collectors.toMap(Filter::name, Function.identity()));
        FilterChainFactory factory = new FilterChainFactory(filterProperties, registry);
        validateAndBuildFilterChains(factory);
        return factory;
    }

    /**
     * 校验过滤器配置并预构建过滤器链。
     * 非法配置（如未知过滤器名、配置值越界）抛异常终止启动。
     */
    private void validateAndBuildFilterChains(FilterChainFactory factory) {
        // 校验 filterChainTimeoutMs 与 retryDelayMs 的叠加场景（warn，不终止启动）
        gatewayProperties.getRoutes().forEach(route -> {
            if (route.getRetry() != null && filterProperties.getFilterChainTimeoutMs() > 0) {
                int retryDelay = route.getRetry().getRetryDelayMs();
                int maxAttempts = route.getRetry().getMaxAttempts();
                long totalRetryDelay = (long) retryDelay * (maxAttempts - 1);
                if (totalRetryDelay >= filterProperties.getFilterChainTimeoutMs()) {
                    log.warn("路由 [{}] retryDelayMs({}) * (maxAttempts({}) - 1) = {}ms "
                            + ">= filterChainTimeoutMs({}ms)，重试延迟可能导致超时提前触发",
                            route.getId(), retryDelay, maxAttempts, totalRetryDelay,
                            filterProperties.getFilterChainTimeoutMs());
                }
            }
        });

        factory.buildChains(gatewayProperties.getRoutes());
        log.info("过滤器链构建完成，共 {} 条路由", gatewayProperties.getRoutes().size());
    }
}
