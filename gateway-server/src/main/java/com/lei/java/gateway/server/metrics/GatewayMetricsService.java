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
package com.lei.java.gateway.server.metrics;

/** 网关可观测性指标服务。 */
public interface GatewayMetricsService {

    /** 标记入站请求开始，用于维护 in-flight 指标。 */
    void onInboundStart();

    /**
     * 标记入站请求结束并记录指标。
     *
     * @param routeId 路由标识
     * @param method HTTP 方法
     * @param status HTTP 状态码
     * @param latencyMs 请求耗时（毫秒）
     * @param success 是否成功
     * @param errorCode 错误码，成功时可传 "-"
     * @param requestBytes 请求体字节数
     * @param responseBytes 响应体字节数
     */
    void onInboundComplete(
            String routeId,
            String method,
            int status,
            long latencyMs,
            boolean success,
            String errorCode,
            long requestBytes,
            long responseBytes);

    /** 标记上游请求开始，用于维护上游 in-flight 指标。 */
    void onUpstreamStart();

    /**
     * 标记上游请求结束并记录指标。
     *
     * @param routeId 路由标识
     * @param status HTTP 状态码
     * @param latencyMs 请求耗时（毫秒）
     * @param success 是否成功
     * @param errorCode 错误码，成功时可传 "-"
     * @param requestBytes 请求体字节数
     * @param responseBytes 响应体字节数
     */
    void onUpstreamComplete(
            String routeId,
            int status,
            long latencyMs,
            boolean success,
            String errorCode,
            long requestBytes,
            long responseBytes);

    /**
     * 记录上游建连指标。
     *
     * @param routeId 路由标识
     * @param latencyMs 建连耗时（毫秒）
     * @param success 建连是否成功
     * @param errorCode 错误码，成功时可传 "-"
     */
    void onUpstreamConnect(String routeId, long latencyMs, boolean success, String errorCode);

    /**
     * 导出 Prometheus 文本格式指标。
     *
     * @return 指标文本
     */
    String scrape();
}
