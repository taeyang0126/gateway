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

/**
 * 上游服务配置。
 *
 * @param scheme 协议，例如 http
 * @param host 上游主机
 * @param port 上游端口
 * @param connectTimeoutMs 建连超时（毫秒）
 * @param readTimeoutMs 读超时（毫秒）
 * @param writeTimeoutMs 写超时（毫秒）
 */
public record UpstreamConfig(
        String scheme,
        String host,
        int port,
        int connectTimeoutMs,
        int readTimeoutMs,
        int writeTimeoutMs) {

    /** 紧凑构造器，作为后续参数校验扩展点。 */
    public UpstreamConfig {
        // no-op
    }
}
