package com.lei.gateway.core.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lei.gateway.core.observability.AccessLogWriter;
import com.lei.gateway.core.observability.MetricsCollector;
import com.lei.gateway.core.plugin.AuthPlugin;
import com.lei.gateway.core.plugin.GatewayPluginProcessor;
import com.lei.gateway.core.plugin.IpAccessPlugin;
import com.lei.gateway.core.plugin.IpRateLimitPlugin;
import com.lei.gateway.core.plugin.Plugin;
import com.lei.gateway.core.plugin.PluginChain;
import com.lei.gateway.core.plugin.PluginConfigResolver;
import com.lei.gateway.core.plugin.PluginRegistry;
import com.lei.gateway.core.plugin.RealIpPlugin;
import com.lei.gateway.core.plugin.UserRateLimitPlugin;
import com.lei.gateway.core.proxy.DrainHandler;
import com.lei.gateway.core.proxy.InFlightRequestTracker;
import com.lei.gateway.core.proxy.NettyServerBootstrap;
import com.lei.gateway.core.proxy.ProxyContext;
import com.lei.gateway.core.proxy.RoutingContext;
import com.lei.gateway.core.proxy.ShutdownCoordinator;
import com.lei.gateway.core.proxy.UpstreamConnectionPool;
import com.lei.gateway.core.proxy.WarmupRunner;
import com.lei.gateway.core.security.AuthProvider;
import com.lei.gateway.core.security.CidrMatcher;
import com.lei.gateway.core.security.ClientIpResolver;
import com.lei.gateway.core.security.JwksKeyProvider;
import com.lei.gateway.core.security.JwtAuthProvider;
import com.lei.gateway.core.security.LocalTokenBucketRateLimiter;
import com.lei.gateway.core.security.RateLimiterEngine;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
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
    SecurityProperties.class,
    ShutdownProperties.class,
    HealthProperties.class
})
public class GatewayAutoConfiguration {

    /** 创建 worker EventLoopGroup Bean，供 NettyServerBootstrap 和 UpstreamConnectionPool 共享。 */
    @Bean
    public EventLoopGroup workerGroup() {
        return new NioEventLoopGroup();
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

    /** 在途请求追踪器。 */
    @Bean
    public InFlightRequestTracker inFlightRequestTracker() {
        return new InFlightRequestTracker();
    }

    /** 排空处理器。 */
    @Bean
    public DrainHandler drainHandler() {
        return new DrainHandler();
    }

    /** 插件注册表。 */
    @Bean
    public PluginRegistry pluginRegistry(List<Plugin> plugins) {
        PluginRegistry registry = new PluginRegistry();
        registry.discoverAndRegister(plugins);
        return registry;
    }

    /** 插件配置解析器。 */
    @Bean
    public PluginConfigResolver pluginConfigResolver(PluginRegistry pluginRegistry) {
        return new PluginConfigResolver(pluginRegistry);
    }

    /** 网关插件处理器。 */
    @Bean
    public GatewayPluginProcessor gatewayPluginProcessor(
            PluginRegistry pluginRegistry,
            PluginConfigResolver configResolver,
            GatewayProperties gatewayProperties,
            MetricsCollector metricsCollector) {
        return new GatewayPluginProcessor(pluginRegistry, configResolver,
                new PluginChain(metricsCollector),
                gatewayProperties.getPlugins());
    }

    /** CIDR 匹配器。 */
    @Bean
    @ConditionalOnMissingBean
    public CidrMatcher cidrMatcher() {
        return new CidrMatcher();
    }

    /** 客户端 IP 解析器。 */
    @Bean
    @ConditionalOnMissingBean
    public ClientIpResolver clientIpResolver(CidrMatcher cidrMatcher) {
        return new ClientIpResolver(cidrMatcher);
    }

    /** 认证提供者。 */
    @Bean
    @ConditionalOnMissingBean
    public AuthProvider authProvider() {
        return new JwtAuthProvider(new JwksKeyProvider());
    }

    /** 限流引擎。 */
    @Bean
    @ConditionalOnMissingBean
    public RateLimiterEngine rateLimiterEngine() {
        return new LocalTokenBucketRateLimiter();
    }

    /** 真实 IP 解析插件。 */
    @Bean
    public RealIpPlugin realIpPlugin(ClientIpResolver clientIpResolver) {
        return new RealIpPlugin(clientIpResolver);
    }

    /** IP 访问控制插件。 */
    @Bean
    public IpAccessPlugin ipAccessPlugin(CidrMatcher cidrMatcher) {
        return new IpAccessPlugin(cidrMatcher);
    }

    /** IP 限流插件。 */
    @Bean
    public IpRateLimitPlugin ipRateLimitPlugin(RateLimiterEngine rateLimiterEngine) {
        return new IpRateLimitPlugin(rateLimiterEngine);
    }

    /** 认证插件。 */
    @Bean
    public AuthPlugin authPlugin(AuthProvider authProvider) {
        return new AuthPlugin(authProvider);
    }

    /** 用户限流插件。 */
    @Bean
    public UserRateLimitPlugin userRateLimitPlugin(RateLimiterEngine rateLimiterEngine) {
        return new UserRateLimitPlugin(rateLimiterEngine);
    }

    /** RoutingHandler 聚合依赖。 */
    @Bean
    public RoutingContext routingContext(RouteResolver routeResolver,
            RequestLimitProperties requestLimitProperties,
            UpstreamConnectionPool connectionPool,
            MetricsCollector metricsCollector,
            AccessLogWriter accessLogWriter,
            ObservabilityProperties observabilityProperties,
            GatewayPluginProcessor pluginProcessor,
            InFlightRequestTracker inFlightRequestTracker,
            DrainHandler drainHandler,
            HealthProperties healthProperties) {
        return new RoutingContext(routeResolver, requestLimitProperties,
                connectionPool, metricsCollector, accessLogWriter,
                observabilityProperties, pluginProcessor,
                inFlightRequestTracker, drainHandler, healthProperties);
    }

    /** ProxyHandler 聚合依赖。 */
    @Bean
    public ProxyContext proxyContext(RequestLimitProperties requestLimitProperties,
            UpstreamConnectionPool connectionPool,
            MetricsCollector metricsCollector,
            AccessLogWriter accessLogWriter,
            ObservabilityProperties observabilityProperties,
            InFlightRequestTracker inFlightRequestTracker) {
        return new ProxyContext(requestLimitProperties, connectionPool,
                metricsCollector, accessLogWriter, observabilityProperties,
                inFlightRequestTracker);
    }

    /** 启动预热执行器。 */
    @Bean
    public WarmupRunner warmupRunner(RouteResolver routeResolver,
            GatewayProperties gatewayProperties,
            UpstreamConnectionPool connectionPool,
            HealthProperties healthProperties,
            ObjectMapper objectMapper) {
        return new WarmupRunner(routeResolver, gatewayProperties,
                connectionPool, healthProperties, objectMapper);
    }

    /** Netty 服务启动器（不再实现 SmartLifecycle）。 */
    @Bean
    public NettyServerBootstrap nettyServerBootstrap(
            GatewayProperties gatewayProperties,
            ObservabilityProperties observabilityProperties,
            RequestLimitProperties requestLimitProperties,
            MetricsCollector metricsCollector,
            RoutingContext routingContext,
            DrainHandler drainHandler,
            ApplicationContext applicationContext,
            EventLoopGroup workerGroup) {
        return new NettyServerBootstrap(gatewayProperties,
                observabilityProperties, requestLimitProperties,
                metricsCollector, routingContext,
                drainHandler, applicationContext, workerGroup);
    }

    /** 优雅停机协调器（SmartLifecycle）。 */
    @Bean
    public ShutdownCoordinator shutdownCoordinator(
            NettyServerBootstrap nettyServerBootstrap,
            DrainHandler drainHandler,
            InFlightRequestTracker inFlightRequestTracker,
            UpstreamConnectionPool connectionPool,
            EventLoopGroup workerGroup,
            ShutdownProperties shutdownProperties,
            WarmupRunner warmupRunner) {
        return new ShutdownCoordinator(nettyServerBootstrap, drainHandler,
                inFlightRequestTracker, connectionPool, workerGroup,
                shutdownProperties, warmupRunner);
    }
}
