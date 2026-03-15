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

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;

class PrometheusGatewayMetricsServiceTests {

    @Test
    void shouldRecordInboundMetricsAndNormalizeLabels() {
        final PrometheusMeterRegistry registry =
                new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        final PrometheusGatewayMetricsService service =
                new PrometheusGatewayMetricsService(registry);
        try {
            service.onInboundStart();
            service.onInboundComplete("  route-a ", " get ", 200, 12, true, " ", 128, 256);
            service.onInboundComplete(null, "", 700, -1, false, null, -5, -8);

            final String metrics = service.scrape();
            assertTrue(metrics.contains("gateway_http_requests_total"));
            assertTrue(metrics.contains("route_id=\"route-a\""));
            assertTrue(metrics.contains("method=\"GET\""));
            assertTrue(metrics.contains("status_class=\"2xx\""));
            assertTrue(metrics.contains("status_class=\"other\""));
            assertTrue(metrics.contains("error_code=\"-\""));
            assertTrue(metrics.contains("gateway_http_request_duration_seconds"));
            assertTrue(metrics.contains("gateway_http_request_bytes_count"));
            assertTrue(metrics.contains("gateway_http_response_bytes_count"));
            assertTrue(metrics.contains("gateway_http_inflight_requests"));
        } finally {
            registry.close();
        }
    }

    @Test
    void shouldClassifyAllStatusClassesForInboundMetrics() {
        final PrometheusMeterRegistry registry =
                new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        final PrometheusGatewayMetricsService service =
                new PrometheusGatewayMetricsService(registry);
        try {
            service.onInboundComplete("route-1xx", "POST", 101, 1, true, "-", 1, 1);
            service.onInboundComplete("route-2xx", "POST", 204, 1, true, "-", 1, 1);
            service.onInboundComplete("route-3xx", "POST", 302, 1, true, "-", 1, 1);
            service.onInboundComplete("route-4xx", "POST", 404, 1, false, "bad_request", 1, 1);
            service.onInboundComplete(
                    "route-5xx", "POST", 503, 1, false, "upstream_unavailable", 1, 1);
            service.onInboundComplete("route-other", "POST", 701, 1, false, "unknown", 1, 1);

            final String metrics = service.scrape();
            assertTrue(metrics.contains("status_class=\"1xx\""));
            assertTrue(metrics.contains("status_class=\"2xx\""));
            assertTrue(metrics.contains("status_class=\"3xx\""));
            assertTrue(metrics.contains("status_class=\"4xx\""));
            assertTrue(metrics.contains("status_class=\"5xx\""));
            assertTrue(metrics.contains("status_class=\"other\""));
            assertTrue(metrics.contains("outcome=\"success\""));
            assertTrue(metrics.contains("outcome=\"failure\""));
        } finally {
            registry.close();
        }
    }

    @Test
    void shouldRecordUpstreamMetricsTimeoutAndConnectionErrors() {
        final PrometheusMeterRegistry registry =
                new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        final PrometheusGatewayMetricsService service =
                new PrometheusGatewayMetricsService(registry);
        try {
            service.onUpstreamStart();
            service.onUpstreamComplete("route-upstream", 504, 50, false, "upstream_timeout", 64, 0);
            service.onUpstreamComplete(
                    "route-upstream", 502, 30, false, "connection_refused", 64, 0);
            service.onUpstreamComplete("route-upstream", 201, 10, true, "-", 64, 128);

            service.onUpstreamConnect("route-upstream", 5, true, "-");
            service.onUpstreamConnect("route-upstream", -1, false, "connect_failed");

            final String metrics = service.scrape();
            assertTrue(metrics.contains("gateway_upstream_requests_total"));
            assertTrue(metrics.contains("gateway_upstream_request_duration_seconds"));
            assertTrue(metrics.contains("gateway_upstream_connect_duration_seconds"));
            assertTrue(metrics.contains("gateway_upstream_request_bytes_count"));
            assertTrue(metrics.contains("gateway_upstream_response_bytes_count"));
            assertTrue(metrics.contains("gateway_upstream_timeouts_total"));
            assertTrue(metrics.contains("gateway_upstream_connection_errors_total"));
            assertTrue(metrics.contains("gateway_upstream_inflight_requests"));
        } finally {
            registry.close();
        }
    }
}
