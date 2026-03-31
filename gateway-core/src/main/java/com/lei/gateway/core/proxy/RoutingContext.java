package com.lei.gateway.core.proxy;

import com.lei.gateway.core.config.HealthProperties;
import com.lei.gateway.core.config.ObservabilityProperties;
import com.lei.gateway.core.config.RequestLimitProperties;
import com.lei.gateway.core.config.RouteResolver;
import com.lei.gateway.core.observability.AccessLogWriter;
import com.lei.gateway.core.observability.MetricsCollector;
import com.lei.gateway.core.plugin.GatewayPluginProcessor;

/**
 * 聚合 {@link RoutingHandler} 的所有依赖，解决构造函数参数过多问题。
 */
public class RoutingContext {

    private final RouteResolver routeResolver;
    private final RequestLimitProperties requestLimitProperties;
    private final UpstreamConnectionPool connectionPool;
    private final MetricsCollector metricsCollector;
    private final AccessLogWriter accessLogWriter;
    private final ObservabilityProperties observabilityProperties;
    private final GatewayPluginProcessor pluginProcessor;
    private final InFlightRequestTracker inFlightTracker;
    private final DrainHandler drainHandler;
    private final HealthProperties healthProperties;

    /** 创建 RoutingContext。 */
    public RoutingContext(RouteResolver routeResolver,
            RequestLimitProperties requestLimitProperties,
            UpstreamConnectionPool connectionPool,
            MetricsCollector metricsCollector,
            AccessLogWriter accessLogWriter,
            ObservabilityProperties observabilityProperties,
            GatewayPluginProcessor pluginProcessor,
            InFlightRequestTracker inFlightTracker,
            DrainHandler drainHandler,
            HealthProperties healthProperties) {
        this.routeResolver = routeResolver;
        this.requestLimitProperties = requestLimitProperties;
        this.connectionPool = connectionPool;
        this.metricsCollector = metricsCollector;
        this.accessLogWriter = accessLogWriter;
        this.observabilityProperties = observabilityProperties;
        this.pluginProcessor = pluginProcessor;
        this.inFlightTracker = inFlightTracker;
        this.drainHandler = drainHandler;
        this.healthProperties = healthProperties;
    }

    public RouteResolver getRouteResolver() {
        return routeResolver;
    }

    public RequestLimitProperties getRequestLimitProperties() {
        return requestLimitProperties;
    }

    public UpstreamConnectionPool getConnectionPool() {
        return connectionPool;
    }

    public MetricsCollector getMetricsCollector() {
        return metricsCollector;
    }

    public AccessLogWriter getAccessLogWriter() {
        return accessLogWriter;
    }

    public ObservabilityProperties getObservabilityProperties() {
        return observabilityProperties;
    }

    /**
     * 获取插件处理器。
     */
    public GatewayPluginProcessor getPluginProcessor() {
        return pluginProcessor;
    }

    public InFlightRequestTracker getInFlightTracker() {
        return inFlightTracker;
    }

    public DrainHandler getDrainHandler() {
        return drainHandler;
    }

    public HealthProperties getHealthProperties() {
        return healthProperties;
    }
}
