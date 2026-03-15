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

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 网关外置配置属性，支持通过 application.yml 和外部配置文件覆盖。 */
@ConfigurationProperties(prefix = "gateway")
public final class GatewayServerProperties {

    private int port = GatewayServerConfig.DEFAULT_PORT;
    private int maxContentLength = GatewayServerConfig.DEFAULT_MAX_CONTENT_LENGTH;
    private boolean pooledAllocatorEnabled = true;
    private boolean healthEndpointEnabled = true;
    private boolean metricsEndpointEnabled = true;
    private int maxPendingPerRoute = GatewayServerConfig.DEFAULT_MAX_PENDING_PER_ROUTE;
    private List<String> managementAllowedClientIps =
            new ArrayList<>(GatewayServerConfig.DEFAULT_MANAGEMENT_ALLOWED_CLIENT_IPS);
    private List<RouteProperties> routes = new ArrayList<>();

    public int getPort() {
        return port;
    }

    public void setPort(final int port) {
        this.port = port;
    }

    public int getMaxContentLength() {
        return maxContentLength;
    }

    public void setMaxContentLength(final int maxContentLength) {
        this.maxContentLength = maxContentLength;
    }

    public boolean isPooledAllocatorEnabled() {
        return pooledAllocatorEnabled;
    }

    public void setPooledAllocatorEnabled(final boolean pooledAllocatorEnabled) {
        this.pooledAllocatorEnabled = pooledAllocatorEnabled;
    }

    public boolean isHealthEndpointEnabled() {
        return healthEndpointEnabled;
    }

    public void setHealthEndpointEnabled(final boolean healthEndpointEnabled) {
        this.healthEndpointEnabled = healthEndpointEnabled;
    }

    public boolean isMetricsEndpointEnabled() {
        return metricsEndpointEnabled;
    }

    public void setMetricsEndpointEnabled(final boolean metricsEndpointEnabled) {
        this.metricsEndpointEnabled = metricsEndpointEnabled;
    }

    public int getMaxPendingPerRoute() {
        return maxPendingPerRoute;
    }

    public void setMaxPendingPerRoute(final int maxPendingPerRoute) {
        this.maxPendingPerRoute = maxPendingPerRoute;
    }

    public List<String> getManagementAllowedClientIps() {
        return List.copyOf(managementAllowedClientIps);
    }

    public void setManagementAllowedClientIps(final List<String> managementAllowedClientIps) {
        if (managementAllowedClientIps == null) {
            this.managementAllowedClientIps = new ArrayList<>();
            return;
        }
        this.managementAllowedClientIps = new ArrayList<>(managementAllowedClientIps);
    }

    public List<RouteProperties> getRoutes() {
        return List.copyOf(routes);
    }

    public void setRoutes(final List<RouteProperties> routes) {
        if (routes == null) {
            this.routes = new ArrayList<>();
            return;
        }
        this.routes = new ArrayList<>(routes);
    }

    /** 单条路由的外置配置。 */
    public static final class RouteProperties {
        private String routeId = "route-main";
        private int priority = 100;
        private MatchType matchType = MatchType.PREFIX;
        private String path = "/api/";
        private HostRewriteMode hostRewriteMode = HostRewriteMode.REWRITE;
        private UpstreamProperties upstream = new UpstreamProperties();

        public String getRouteId() {
            return routeId;
        }

        public void setRouteId(final String routeId) {
            this.routeId = routeId;
        }

        public int getPriority() {
            return priority;
        }

        public void setPriority(final int priority) {
            this.priority = priority;
        }

        public MatchType getMatchType() {
            return matchType;
        }

        public void setMatchType(final MatchType matchType) {
            this.matchType = matchType;
        }

        public String getPath() {
            return path;
        }

        public void setPath(final String path) {
            this.path = path;
        }

        public HostRewriteMode getHostRewriteMode() {
            return hostRewriteMode;
        }

        public void setHostRewriteMode(final HostRewriteMode hostRewriteMode) {
            this.hostRewriteMode = hostRewriteMode;
        }

        public UpstreamProperties getUpstream() {
            return new UpstreamProperties(upstream);
        }

        public void setUpstream(final UpstreamProperties upstream) {
            this.upstream =
                    new UpstreamProperties(
                            Objects.requireNonNull(upstream, "upstream must not be null"));
        }
    }

    /** 上游配置的外置参数。 */
    public static final class UpstreamProperties {
        private String scheme = "http";
        private String host = "127.0.0.1";
        private int port = 9001;
        private int connectTimeoutMs = 1000;
        private int readTimeoutMs = 1000;
        private int writeTimeoutMs = 1000;

        public UpstreamProperties() {
            // default constructor
        }

        private UpstreamProperties(final UpstreamProperties source) {
            this.scheme = source.scheme;
            this.host = source.host;
            this.port = source.port;
            this.connectTimeoutMs = source.connectTimeoutMs;
            this.readTimeoutMs = source.readTimeoutMs;
            this.writeTimeoutMs = source.writeTimeoutMs;
        }

        public String getScheme() {
            return scheme;
        }

        public void setScheme(final String scheme) {
            this.scheme = scheme;
        }

        public String getHost() {
            return host;
        }

        public void setHost(final String host) {
            this.host = host;
        }

        public int getPort() {
            return port;
        }

        public void setPort(final int port) {
            this.port = port;
        }

        public int getConnectTimeoutMs() {
            return connectTimeoutMs;
        }

        public void setConnectTimeoutMs(final int connectTimeoutMs) {
            this.connectTimeoutMs = connectTimeoutMs;
        }

        public int getReadTimeoutMs() {
            return readTimeoutMs;
        }

        public void setReadTimeoutMs(final int readTimeoutMs) {
            this.readTimeoutMs = readTimeoutMs;
        }

        public int getWriteTimeoutMs() {
            return writeTimeoutMs;
        }

        public void setWriteTimeoutMs(final int writeTimeoutMs) {
            this.writeTimeoutMs = writeTimeoutMs;
        }
    }
}
