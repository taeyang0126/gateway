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
package com.lei.java.gateway.server.config;

import java.util.List;
import java.util.Objects;

/** 将 Spring 绑定配置转换为运行时网关配置对象。 */
public final class GatewayServerConfigFactory {

    public GatewayServerConfig create(final GatewayServerProperties properties) {
        final GatewayServerProperties nonNullProperties =
                Objects.requireNonNull(properties, "properties must not be null");
        final List<RouteConfig> routes =
                nonNullProperties.getRoutes().stream().map(this::toRouteConfig).toList();
        return new GatewayServerConfig(
                nonNullProperties.getPort(),
                nonNullProperties.getMaxContentLength(),
                nonNullProperties.isPooledAllocatorEnabled(),
                routes,
                nonNullProperties.isHealthEndpointEnabled(),
                nonNullProperties.isMetricsEndpointEnabled(),
                nonNullProperties.getManagementAllowedClientIps(),
                nonNullProperties.getMaxPendingPerRoute());
    }

    private RouteConfig toRouteConfig(
            final GatewayServerProperties.RouteProperties routeProperties) {
        final GatewayServerProperties.RouteProperties nonNullRoute =
                Objects.requireNonNull(routeProperties, "route properties must not be null");
        final GatewayServerProperties.UpstreamProperties upstreamProperties =
                Objects.requireNonNull(
                        nonNullRoute.getUpstream(), "route upstream must not be null");
        final UpstreamConfig upstreamConfig =
                new UpstreamConfig(
                        normalizeAndValidateScheme(
                                requiredText(upstreamProperties.getScheme(), "upstream.scheme")),
                        requiredText(upstreamProperties.getHost(), "upstream.host"),
                        upstreamProperties.getPort(),
                        upstreamProperties.getConnectTimeoutMs(),
                        upstreamProperties.getReadTimeoutMs(),
                        upstreamProperties.getWriteTimeoutMs());
        return new RouteConfig(
                requiredText(nonNullRoute.getRouteId(), "route.routeId"),
                nonNullRoute.getPriority(),
                Objects.requireNonNull(
                        nonNullRoute.getMatchType(), "route.matchType must not be null"),
                requiredText(nonNullRoute.getPath(), "route.path"),
                Objects.requireNonNull(
                        nonNullRoute.getHostRewriteMode(),
                        "route.hostRewriteMode must not be null"),
                upstreamConfig);
    }

    private static String requiredText(final String value, final String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }

    private static String normalizeAndValidateScheme(final String scheme) {
        final String normalized = scheme.toLowerCase(java.util.Locale.ROOT);
        if (!"http".equals(normalized)) {
            throw new IllegalArgumentException(
                    "upstream.scheme only supports http in stage1, actual=" + scheme);
        }
        return normalized;
    }
}
