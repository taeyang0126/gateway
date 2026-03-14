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
package com.lei.java.gateway.server.proxy;

import com.lei.java.gateway.server.config.RouteConfig;

/** 阶段 1 超时策略。 */
public interface TimeoutPolicy {
    /**
     * 连接超时（毫秒）。
     *
     * @param route 命中的路由
     * @return 连接超时毫秒值
     */
    int connectTimeoutMs(RouteConfig route);

    /**
     * 读超时（毫秒）。
     *
     * @param route 命中的路由
     * @return 读超时毫秒值
     */
    int readTimeoutMs(RouteConfig route);

    /**
     * 写超时（毫秒）。
     *
     * @param route 命中的路由
     * @return 写超时毫秒值
     */
    int writeTimeoutMs(RouteConfig route);
}
