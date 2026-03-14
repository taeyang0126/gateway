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

import java.util.Objects;

/**
 * 静态路由配置。
 *
 * @param routeId 路由标识
 * @param priority 优先级，越大越优先
 * @param matchType 匹配类型（EXACT/PREFIX）
 * @param path 路由路径
 * @param hostRewriteMode Host 处理策略
 * @param upstream 上游配置
 */
public record RouteConfig(
        String routeId,
        int priority,
        MatchType matchType,
        String path,
        HostRewriteMode hostRewriteMode,
        UpstreamConfig upstream) {
    /**
     * 构造函数，执行字段基础校验。
     *
     * @param routeId 路由标识
     * @param priority 优先级，越大越优先
     * @param matchType 匹配类型（EXACT/PREFIX）
     * @param path 路由路径
     * @param hostRewriteMode Host 处理策略
     * @param upstream 上游配置
     */
    public RouteConfig {
        routeId = Objects.requireNonNull(routeId, "routeId must not be null");
        matchType = Objects.requireNonNull(matchType, "matchType must not be null");
        path = Objects.requireNonNull(path, "path must not be null");
        hostRewriteMode =
                Objects.requireNonNull(hostRewriteMode, "hostRewriteMode must not be null");
        upstream = Objects.requireNonNull(upstream, "upstream must not be null");
        if (!path.startsWith("/")) {
            throw new IllegalArgumentException("path must start with '/'");
        }
    }
}
