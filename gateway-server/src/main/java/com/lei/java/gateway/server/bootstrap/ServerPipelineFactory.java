/*
 * Copyright (c) 2026 lei.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.lei.java.gateway.server.bootstrap;

import java.util.Objects;

import com.lei.java.gateway.server.config.GatewayServerConfig;
import com.lei.java.gateway.server.http.DefaultErrorResponseMapper;
import com.lei.java.gateway.server.http.DefaultHeaderPolicyService;
import com.lei.java.gateway.server.http.ErrorResponseMapper;
import com.lei.java.gateway.server.http.HeaderPolicyService;
import com.lei.java.gateway.server.metrics.GatewayMetricsService;
import com.lei.java.gateway.server.metrics.NoopGatewayMetricsService;
import com.lei.java.gateway.server.proxy.DefaultTimeoutPolicy;
import com.lei.java.gateway.server.proxy.TimeoutPolicy;
import com.lei.java.gateway.server.routing.RouteService;
import com.lei.java.gateway.server.routing.StaticRouteService;

import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpServerKeepAliveHandler;

final class ServerPipelineFactory {

    private final GatewayServerConfig config;
    private final RouteService routeService;
    private final HeaderPolicyService headerPolicyService;
    private final TimeoutPolicy timeoutPolicy;
    private final ErrorResponseMapper errorResponseMapper;
    private final GatewayMetricsService gatewayMetricsService;

    ServerPipelineFactory(final GatewayServerConfig config) {
        this(
                config,
                new StaticRouteService(),
                new DefaultHeaderPolicyService(),
                new DefaultTimeoutPolicy(),
                new DefaultErrorResponseMapper(),
                new NoopGatewayMetricsService());
    }

    ServerPipelineFactory(
            final GatewayServerConfig config,
            final RouteService routeService,
            final HeaderPolicyService headerPolicyService,
            final TimeoutPolicy timeoutPolicy,
            final ErrorResponseMapper errorResponseMapper,
            final GatewayMetricsService gatewayMetricsService) {
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.routeService = Objects.requireNonNull(routeService, "routeService must not be null");
        this.headerPolicyService =
                Objects.requireNonNull(headerPolicyService, "headerPolicyService must not be null");
        this.timeoutPolicy =
                Objects.requireNonNull(timeoutPolicy, "timeoutPolicy must not be null");
        this.errorResponseMapper =
                Objects.requireNonNull(errorResponseMapper, "errorResponseMapper must not be null");
        this.gatewayMetricsService =
                Objects.requireNonNull(
                        gatewayMetricsService, "gatewayMetricsService must not be null");
    }

    void configure(final ChannelPipeline pipeline) {
        Objects.requireNonNull(pipeline, "pipeline must not be null");
        pipeline.addLast(new HttpServerCodec());
        pipeline.addLast(new HttpObjectAggregator(config.maxContentLength()));
        pipeline.addLast(new HttpServerKeepAliveHandler());
        pipeline.addLast(
                new DefaultHttpServerHandler(
                        config.maxContentLength(),
                        config.routes(),
                        routeService,
                        headerPolicyService,
                        timeoutPolicy,
                        errorResponseMapper,
                        gatewayMetricsService));
    }
}
