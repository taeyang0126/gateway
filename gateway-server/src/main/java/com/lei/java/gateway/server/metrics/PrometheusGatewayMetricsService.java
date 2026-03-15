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

import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;

/** 基于 Micrometer PrometheusRegistry 的网关指标实现。 */
public final class PrometheusGatewayMetricsService implements GatewayMetricsService {

    private final MeterRegistry meterRegistry;
    private final PrometheusMeterRegistry prometheusMeterRegistry;
    private final AtomicInteger inboundInflight;
    private final AtomicInteger upstreamInflight;

    public PrometheusGatewayMetricsService(final PrometheusMeterRegistry prometheusMeterRegistry) {
        this.prometheusMeterRegistry =
                Objects.requireNonNull(
                        prometheusMeterRegistry, "prometheusMeterRegistry must not be null");
        this.meterRegistry = this.prometheusMeterRegistry;
        this.inboundInflight = new AtomicInteger();
        this.upstreamInflight = new AtomicInteger();

        Gauge.builder("gateway_http_inflight_requests", inboundInflight, AtomicInteger::get)
                .register(meterRegistry);
        Gauge.builder("gateway_upstream_inflight_requests", upstreamInflight, AtomicInteger::get)
                .register(meterRegistry);
    }

    @Override
    public void onInboundStart() {
        inboundInflight.incrementAndGet();
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
        decrementSafely(inboundInflight);
        final String normalizedRouteId = normalize(routeId);
        final String normalizedMethod = normalizeMethod(method);
        final String statusClass = toStatusClass(status);
        final String outcome = toOutcome(success);
        final String normalizedErrorCode = normalizeErrorCode(errorCode);

        Counter.builder("gateway_http_requests")
                .tag("route_id", normalizedRouteId)
                .tag("method", normalizedMethod)
                .tag("status_class", statusClass)
                .tag("outcome", outcome)
                .tag("error_code", normalizedErrorCode)
                .register(meterRegistry)
                .increment();

        Timer.builder("gateway_http_request_duration")
                .publishPercentiles(0.95D, 0.99D)
                .tag("route_id", normalizedRouteId)
                .tag("method", normalizedMethod)
                .tag("status_class", statusClass)
                .tag("outcome", outcome)
                .tag("error_code", normalizedErrorCode)
                .register(meterRegistry)
                .record(Math.max(latencyMs, 0), TimeUnit.MILLISECONDS);

        DistributionSummary.builder("gateway_http_request_bytes")
                .baseUnit("bytes")
                .tag("route_id", normalizedRouteId)
                .tag("method", normalizedMethod)
                .tag("outcome", outcome)
                .register(meterRegistry)
                .record(Math.max(requestBytes, 0));

        DistributionSummary.builder("gateway_http_response_bytes")
                .baseUnit("bytes")
                .tag("route_id", normalizedRouteId)
                .tag("method", normalizedMethod)
                .tag("outcome", outcome)
                .register(meterRegistry)
                .record(Math.max(responseBytes, 0));
    }

    @Override
    public void onUpstreamStart() {
        upstreamInflight.incrementAndGet();
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
        decrementSafely(upstreamInflight);
        final String normalizedRouteId = normalize(routeId);
        final String statusClass = toStatusClass(status);
        final String outcome = toOutcome(success);
        final String normalizedErrorCode = normalizeErrorCode(errorCode);

        Counter.builder("gateway_upstream_requests")
                .tag("route_id", normalizedRouteId)
                .tag("status_class", statusClass)
                .tag("outcome", outcome)
                .tag("error_code", normalizedErrorCode)
                .register(meterRegistry)
                .increment();

        Timer.builder("gateway_upstream_request_duration")
                .publishPercentiles(0.95D, 0.99D)
                .tag("route_id", normalizedRouteId)
                .tag("status_class", statusClass)
                .tag("outcome", outcome)
                .tag("error_code", normalizedErrorCode)
                .register(meterRegistry)
                .record(Math.max(latencyMs, 0), TimeUnit.MILLISECONDS);

        DistributionSummary.builder("gateway_upstream_request_bytes")
                .baseUnit("bytes")
                .tag("route_id", normalizedRouteId)
                .tag("outcome", outcome)
                .register(meterRegistry)
                .record(Math.max(requestBytes, 0));

        DistributionSummary.builder("gateway_upstream_response_bytes")
                .baseUnit("bytes")
                .tag("route_id", normalizedRouteId)
                .tag("outcome", outcome)
                .register(meterRegistry)
                .record(Math.max(responseBytes, 0));

        if (!success && normalizedErrorCode.contains("TIMEOUT")) {
            Counter.builder("gateway_upstream_timeouts")
                    .tag("route_id", normalizedRouteId)
                    .tag("error_code", normalizedErrorCode)
                    .register(meterRegistry)
                    .increment();
        }
        if (!success
                && (normalizedErrorCode.contains("UNAVAILABLE")
                        || normalizedErrorCode.contains("CONNECTION")
                        || normalizedErrorCode.contains("CONNECT"))) {
            Counter.builder("gateway_upstream_connection_errors")
                    .tag("route_id", normalizedRouteId)
                    .tag("error_code", normalizedErrorCode)
                    .register(meterRegistry)
                    .increment();
        }
    }

    @Override
    public void onUpstreamConnect(
            final String routeId,
            final long latencyMs,
            final boolean success,
            final String errorCode) {
        final String normalizedRouteId = normalize(routeId);
        final String outcome = toOutcome(success);
        final String normalizedErrorCode = normalizeErrorCode(errorCode);

        Timer.builder("gateway_upstream_connect_duration")
                .publishPercentiles(0.95D, 0.99D)
                .tag("route_id", normalizedRouteId)
                .tag("outcome", outcome)
                .tag("error_code", normalizedErrorCode)
                .register(meterRegistry)
                .record(Math.max(latencyMs, 0), TimeUnit.MILLISECONDS);
    }

    @Override
    public String scrape() {
        return prometheusMeterRegistry.scrape();
    }

    private static void decrementSafely(final AtomicInteger counter) {
        counter.updateAndGet(value -> Math.max(0, value - 1));
    }

    private static String toStatusClass(final int status) {
        if (status >= 100 && status < 200) {
            return "1xx";
        }
        if (status >= 200 && status < 300) {
            return "2xx";
        }
        if (status >= 300 && status < 400) {
            return "3xx";
        }
        if (status >= 400 && status < 500) {
            return "4xx";
        }
        if (status >= 500 && status < 600) {
            return "5xx";
        }
        return "other";
    }

    private static String toOutcome(final boolean success) {
        if (success) {
            return "success";
        }
        return "failure";
    }

    private static String normalizeMethod(final String method) {
        if (method == null || method.isBlank()) {
            return "UNKNOWN";
        }
        return method.trim().toUpperCase(Locale.ROOT);
    }

    private static String normalizeErrorCode(final String errorCode) {
        if (errorCode == null || errorCode.isBlank()) {
            return "-";
        }
        return errorCode.trim().toUpperCase(Locale.ROOT);
    }

    private static String normalize(final String value) {
        if (value == null || value.isBlank()) {
            return "-";
        }
        return value.trim();
    }
}
