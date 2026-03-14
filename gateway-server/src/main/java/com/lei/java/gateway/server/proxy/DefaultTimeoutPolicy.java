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

import java.util.Objects;

import com.lei.java.gateway.server.config.RouteConfig;

/** 基于路由配置的默认超时策略实现。 */
public final class DefaultTimeoutPolicy implements TimeoutPolicy {

    @Override
    public int connectTimeoutMs(final RouteConfig route) {
        return positive(
                "connectTimeoutMs",
                Objects.requireNonNull(route, "route must not be null")
                        .upstream()
                        .connectTimeoutMs());
    }

    @Override
    public int readTimeoutMs(final RouteConfig route) {
        return positive(
                "readTimeoutMs",
                Objects.requireNonNull(route, "route must not be null").upstream().readTimeoutMs());
    }

    @Override
    public int writeTimeoutMs(final RouteConfig route) {
        return positive(
                "writeTimeoutMs",
                Objects.requireNonNull(route, "route must not be null")
                        .upstream()
                        .writeTimeoutMs());
    }

    private static int positive(final String field, final int value) {
        if (value <= 0) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return value;
    }
}
