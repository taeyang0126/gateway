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
package com.lei.java.gateway.server.routing;

import java.util.List;
import java.util.Optional;

import com.lei.java.gateway.server.config.RouteConfig;

/** 路由匹配服务。 */
public interface RouteService {
    /**
     * 根据请求路径选择目标路由。
     *
     * @param requestPath 请求路径
     * @param routes 路由列表
     * @return 命中的路由，未命中时返回空
     */
    Optional<RouteConfig> select(String requestPath, List<RouteConfig> routes);
}
