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
 */
public record GatewayServerConfig(
        int port, int maxContentLength, boolean pooledAllocatorEnabled, List<RouteConfig> routes) {

    public static final int DEFAULT_PORT = 8080;
    public static final int DEFAULT_MAX_CONTENT_LENGTH = 1024 * 1024;

    /**
     * 构造函数，执行基础参数校验。
     *
     * @param port 服务监听端口，范围 [0, 65535]
     * @param maxContentLength HTTP 聚合包体最大长度，必须大于 0
     * @param pooledAllocatorEnabled 是否启用池化内存分配器
     * @param routes 静态路由配置列表
     */
    public GatewayServerConfig {
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("port must be between 0 and 65535");
        }
        if (maxContentLength <= 0) {
            throw new IllegalArgumentException("maxContentLength must be positive");
        }
        routes = List.copyOf(Objects.requireNonNull(routes, "routes must not be null"));
    }

    /**
     * 返回阶段 1 的默认配置。
     *
     * @return 默认配置对象
     */
    public static GatewayServerConfig defaultConfig() {
        return new GatewayServerConfig(DEFAULT_PORT, DEFAULT_MAX_CONTENT_LENGTH, true, List.of());
    }
}
