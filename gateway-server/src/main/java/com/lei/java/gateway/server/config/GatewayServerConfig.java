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

/**
 * 网关服务端启动配置。
 *
 * @param port 服务监听端口，范围 [0, 65535]
 * @param maxContentLength HTTP 聚合包体最大长度，必须大于 0
 * @param pooledAllocatorEnabled 是否启用池化内存分配器
 * @param routes 静态路由配置列表
 * @param healthEndpointEnabled 是否启用健康检查端点
 * @param metricsEndpointEnabled 是否启用 Prometheus 指标端点
 * @param managementAllowedClientIps 管理端点允许访问的客户端 IP 白名单
 * @param maxPendingPerRoute 单路由允许的最大待转发请求数
 */
public record GatewayServerConfig(
        int port,
        int maxContentLength,
        boolean pooledAllocatorEnabled,
        List<RouteConfig> routes,
        boolean healthEndpointEnabled,
        boolean metricsEndpointEnabled,
        List<String> managementAllowedClientIps,
        int maxPendingPerRoute) {

    public static final int DEFAULT_PORT = 8080;
    public static final int DEFAULT_MAX_CONTENT_LENGTH = 5 * 1024 * 1024;
    public static final int DEFAULT_MAX_PENDING_PER_ROUTE = 1024;
    public static final List<String> DEFAULT_MANAGEMENT_ALLOWED_CLIENT_IPS =
            List.of("127.0.0.1", "::1", "0:0:0:0:0:0:0:1");

    /**
     * 构造函数，执行基础参数校验。
     *
     * @param port 服务监听端口，范围 [0, 65535]
     * @param maxContentLength HTTP 聚合包体最大长度，必须大于 0
     * @param pooledAllocatorEnabled 是否启用池化内存分配器
     * @param routes 静态路由配置列表
     * @param healthEndpointEnabled 是否启用健康检查端点
     * @param metricsEndpointEnabled 是否启用 Prometheus 指标端点
     * @param managementAllowedClientIps 管理端点允许访问的客户端 IP 白名单
     * @param maxPendingPerRoute 单路由允许的最大待转发请求数
     */
    public GatewayServerConfig {
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("port must be between 0 and 65535");
        }
        if (maxContentLength <= 0) {
            throw new IllegalArgumentException("maxContentLength must be positive");
        }
        if (maxPendingPerRoute <= 0) {
            throw new IllegalArgumentException("maxPendingPerRoute must be positive");
        }
        routes = List.copyOf(Objects.requireNonNull(routes, "routes must not be null"));
        managementAllowedClientIps =
                List.copyOf(
                        Objects.requireNonNull(
                                managementAllowedClientIps,
                                "managementAllowedClientIps must not be null"));
    }

    /**
     * 兼容阶段 1 原有构造方式，使用默认管理端点与队列参数。
     *
     * @param port 服务监听端口，范围 [0, 65535]
     * @param maxContentLength HTTP 聚合包体最大长度，必须大于 0
     * @param pooledAllocatorEnabled 是否启用池化内存分配器
     * @param routes 静态路由配置列表
     */
    public GatewayServerConfig(
            final int port,
            final int maxContentLength,
            final boolean pooledAllocatorEnabled,
            final List<RouteConfig> routes) {
        this(
                port,
                maxContentLength,
                pooledAllocatorEnabled,
                routes,
                true,
                true,
                DEFAULT_MANAGEMENT_ALLOWED_CLIENT_IPS,
                DEFAULT_MAX_PENDING_PER_ROUTE);
    }

    /**
     * 返回阶段 1 的默认配置。
     *
     * @return 默认配置对象
     */
    public static GatewayServerConfig defaultConfig() {
        return new GatewayServerConfig(
                DEFAULT_PORT,
                DEFAULT_MAX_CONTENT_LENGTH,
                true,
                List.of(),
                true,
                true,
                DEFAULT_MANAGEMENT_ALLOWED_CLIENT_IPS,
                DEFAULT_MAX_PENDING_PER_ROUTE);
    }
}
