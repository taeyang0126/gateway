package com.lei.gateway.proxy;

import static org.assertj.core.api.Assertions.assertThat;

import com.lei.gateway.config.GatewayProperties;
import com.lei.gateway.config.HealthProperties;
import com.lei.gateway.config.ObservabilityProperties;
import com.lei.gateway.config.RequestLimitProperties;
import com.lei.gateway.config.RouteResolver;
import com.lei.gateway.observability.AccessLogWriter;
import com.lei.gateway.observability.MetricsCollector;
import com.lei.gateway.plugin.GatewayPluginProcessor;
import com.lei.gateway.plugin.PluginChain;
import com.lei.gateway.plugin.PluginConfigResolver;
import com.lei.gateway.plugin.PluginRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.CharsetUtil;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Arbitraries;

/**
 * Property 6: Readiness 端点状态矩阵。
 *
 * <p>枚举 {@code (isDraining, isWarmupComplete)} 组合，
 * 验证 {@code /health/ready} 响应状态码和 body。
 */
class HealthEndpointPropertyTest {

    @Provide
    Arbitrary<Boolean> booleans() {
        return Arbitraries.of(true, false);
    }

    // Feature: graceful-shutdown, Property 6: Readiness 端点状态矩阵
    @Property(tries = 100)
    void readinessEndpointStateMatrix(
            @ForAll("booleans") boolean isDraining,
            @ForAll("booleans") boolean isWarmupComplete) throws Exception {

        DrainHandler drainHandler = new DrainHandler();
        if (isDraining) {
            drainHandler.activateDrain();
        }

        HealthProperties healthProperties = new HealthProperties();
        // warmupComplete == true → startupDelay 已过（设为 0）
        // warmupComplete == false → startupDelay 未过（设为很大值）
        healthProperties.setStartupDelaySeconds(
                isWarmupComplete ? 0 : 3600);

        RoutingHandler handler = createHandler(drainHandler, healthProperties);
        EmbeddedChannel channel = new EmbeddedChannel(handler);

        DefaultFullHttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.GET, "/health/ready");
        request.headers().set(HttpHeaderNames.HOST, "localhost");
        channel.writeInbound(request);

        FullHttpResponse response = channel.readOutbound();
        assertThat(response).isNotNull();

        String body = response.content().toString(CharsetUtil.UTF_8);

        if (isDraining) {
            assertThat(response.status())
                    .isEqualTo(HttpResponseStatus.SERVICE_UNAVAILABLE);
            assertThat(body).contains("\"reason\":\"draining\"");
        } else if (!isWarmupComplete) {
            assertThat(response.status())
                    .isEqualTo(HttpResponseStatus.SERVICE_UNAVAILABLE);
            assertThat(body).contains("\"reason\":\"warming_up\"");
        } else {
            assertThat(response.status())
                    .isEqualTo(HttpResponseStatus.OK);
            assertThat(body).contains("\"status\":\"UP\"");
        }

        response.release();
        channel.finishAndReleaseAll();
    }

    private RoutingHandler createHandler(DrainHandler drainHandler,
            HealthProperties healthProperties) {
        ObservabilityProperties observabilityProperties =
                new ObservabilityProperties();
        RequestLimitProperties requestLimitProperties =
                new RequestLimitProperties();
        MetricsCollector metricsCollector = new MetricsCollector(
                new SimpleMeterRegistry(), observabilityProperties);
        AccessLogWriter accessLogWriter = new AccessLogWriter(
                observabilityProperties, new ObjectMapper());
        GatewayProperties gatewayProperties = new GatewayProperties();
        gatewayProperties.setRoutes(List.of());
        RouteResolver routeResolver = new RouteResolver(gatewayProperties);
        InFlightRequestTracker inFlightTracker =
                new InFlightRequestTracker();
        RoutingContext routingCtx = new RoutingContext(
                routeResolver, requestLimitProperties, null,
                metricsCollector, accessLogWriter, observabilityProperties,
                new GatewayPluginProcessor(new PluginRegistry(),
                        new PluginConfigResolver(new PluginRegistry(), new ObjectMapper()),
                        new PluginChain(metricsCollector), List.of()),
                inFlightTracker, drainHandler, healthProperties);
        return new RoutingHandler(routingCtx,
                new AtomicInteger(0), Instant.now());
    }
}
