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

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class NoopGatewayMetricsServiceTests {

    @Test
    void shouldReturnEmptyScrapeAndIgnoreAllMetricsEvents() {
        final NoopGatewayMetricsService service = new NoopGatewayMetricsService();

        service.onInboundStart();
        service.onInboundComplete("route-a", "GET", 200, 1, true, "-", 10, 20);
        service.onUpstreamStart();
        service.onUpstreamComplete("route-a", 200, 1, true, "-", 10, 20);
        service.onUpstreamConnect("route-a", 1, true, "-");

        assertEquals("", service.scrape());
    }
}
