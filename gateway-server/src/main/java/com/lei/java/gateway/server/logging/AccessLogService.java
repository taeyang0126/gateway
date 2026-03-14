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
package com.lei.java.gateway.server.logging;

/** 访问日志服务。 */
public interface AccessLogService {
    /**
     * 成功请求日志。
     *
     * @param traceId 追踪 ID
     * @param uri 原始 URI
     * @param status HTTP 状态码
     * @param latencyMs 耗时（毫秒）
     * @param upstream 上游地址
     */
    void logSuccess(String traceId, String uri, int status, long latencyMs, String upstream);

    /**
     * 失败请求日志。
     *
     * @param traceId 追踪 ID
     * @param uri 原始 URI
     * @param status HTTP 状态码
     * @param errorCode 错误码
     * @param latencyMs 耗时（毫秒）
     * @param upstream 上游地址
     */
    void logFailure(
            String traceId,
            String uri,
            int status,
            String errorCode,
            long latencyMs,
            String upstream);
}
