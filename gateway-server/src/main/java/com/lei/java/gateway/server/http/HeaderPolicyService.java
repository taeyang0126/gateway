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
package com.lei.java.gateway.server.http;

import com.lei.java.gateway.server.config.RouteConfig;

import io.netty.handler.codec.http.HttpHeaders;

/** 请求头处理策略服务。 */
public interface HeaderPolicyService {
    /**
     * 将入站请求头按阶段 1 策略应用到出站请求。
     *
     * @param inboundRequestHeaders 入站请求头
     * @param outboundRequestHeaders 出站请求头
     * @param route 命中的路由
     * @param clientIp 客户端 IP
     * @param traceId 关联链路的 TraceId
     */
    void applyRequestHeaders(
            HttpHeaders inboundRequestHeaders,
            HttpHeaders outboundRequestHeaders,
            RouteConfig route,
            String clientIp,
            String traceId);
}
