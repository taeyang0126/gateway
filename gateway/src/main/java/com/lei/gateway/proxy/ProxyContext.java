package com.lei.gateway.proxy;

import com.lei.gateway.config.ObservabilityProperties;
import com.lei.gateway.config.RequestLimitProperties;
import com.lei.gateway.observability.AccessLogWriter;
import com.lei.gateway.observability.MetricsCollector;
import com.lei.gateway.plugin.GatewayPluginProcessor;

/**
 * 聚合 {@link ProxyHandler} 的所有依赖，解决构造函数参数过多问题。
 */
public class ProxyContext {

    private final RequestLimitProperties requestLimitProperties;
    private final UpstreamConnectionPool connectionPool;
    private final MetricsCollector metricsCollector;
    private final AccessLogWriter accessLogWriter;
    private final ObservabilityProperties observabilityProperties;
    private final InFlightRequestTracker inFlightTracker;
    private final GatewayPluginProcessor pluginProcessor;

    /** 创建 ProxyContext。 */
    public ProxyContext(RequestLimitProperties requestLimitProperties,
            UpstreamConnectionPool connectionPool,
            MetricsCollector metricsCollector,
            AccessLogWriter accessLogWriter,
            ObservabilityProperties observabilityProperties,
            InFlightRequestTracker inFlightTracker,
            GatewayPluginProcessor pluginProcessor) {
        this.requestLimitProperties = requestLimitProperties;
        this.connectionPool = connectionPool;
        this.metricsCollector = metricsCollector;
        this.accessLogWriter = accessLogWriter;
        this.observabilityProperties = observabilityProperties;
        this.inFlightTracker = inFlightTracker;
        this.pluginProcessor = pluginProcessor;
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

    public InFlightRequestTracker getInFlightTracker() {
        return inFlightTracker;
    }

    /**
     * 获取插件处理器。
     */
    public GatewayPluginProcessor getPluginProcessor() {
        return pluginProcessor;
    }
}
