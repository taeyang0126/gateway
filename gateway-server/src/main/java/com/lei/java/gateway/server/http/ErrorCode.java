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

/** 网关错误码。 */
public enum ErrorCode {
    /** 请求参数或请求格式非法。 */
    BAD_REQUEST,
    /** 上游请求超时。 */
    UPSTREAM_TIMEOUT,
    /** 上游不可达或不可用。 */
    UPSTREAM_UNAVAILABLE,
    /** 网关内部未分类错误。 */
    GATEWAY_INTERNAL_ERROR,
    /** 未匹配到任何路由。 */
    ROUTE_NOT_FOUND,
    /** 管理端点访问被拒绝。 */
    MANAGEMENT_ENDPOINT_FORBIDDEN,
    /** 单路由上游待处理队列已满。 */
    UPSTREAM_BACKLOG_OVERFLOW,
    /** 客户端通道已失活，写回失败。 */
    CLIENT_CHANNEL_INACTIVE,
    /** 向客户端写回响应失败。 */
    CLIENT_WRITE_FAILED,
    /** 连接上游失败。 */
    UPSTREAM_CONNECT_FAILED
}
