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

/** 空实现，用于未接入指标采集的场景。 */
public final class NoopGatewayMetricsService implements GatewayMetricsService {

    @Override
    public void onInboundStart() {
        // no-op
    }

    @Override
    public void onInboundComplete(
            final String routeId,
            final String method,
            final int status,
            final long latencyMs,
            final boolean success,
            final String errorCode,
            final long requestBytes,
            final long responseBytes) {
        // no-op
    }

    @Override
    public void onUpstreamStart() {
        // no-op
    }

    @Override
    public void onUpstreamComplete(
            final String routeId,
            final int status,
            final long latencyMs,
            final boolean success,
            final String errorCode,
            final long requestBytes,
            final long responseBytes) {
        // no-op
    }

    @Override
    public void onUpstreamConnect(
            final String routeId,
            final long latencyMs,
            final boolean success,
            final String errorCode) {
        // no-op
    }

    @Override
    public String scrape() {
        return "";
    }
}
