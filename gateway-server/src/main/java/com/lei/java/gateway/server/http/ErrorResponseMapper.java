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

/** 错误映射器。 */
public interface ErrorResponseMapper {
    /**
     * 将异常映射为统一错误响应。
     *
     * @param throwable 原始异常
     * @param traceId 当前链路 TraceId
     * @return 统一错误响应
     */
    ErrorResponse map(Throwable throwable, String traceId);
}
