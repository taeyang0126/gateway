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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;

class GatewayServerConfigFactoryTests {

    @Test
    void shouldBuildGatewayServerConfigFromProperties() {
        final GatewayServerProperties properties = new GatewayServerProperties();
        properties.setPort(18080);
        properties.setMaxContentLength(2048);
        properties.setPooledAllocatorEnabled(false);

        final GatewayServerProperties.RouteProperties route =
                new GatewayServerProperties.RouteProperties();
        route.setRouteId("route-1");
        route.setPriority(10);
        route.setMatchType(MatchType.EXACT);
        route.setPath("/api/ping");
        route.setHostRewriteMode(HostRewriteMode.PRESERVE);

        final GatewayServerProperties.UpstreamProperties upstream =
                new GatewayServerProperties.UpstreamProperties();
        upstream.setScheme("http");
        upstream.setHost("127.0.0.1");
        upstream.setPort(9002);
        upstream.setConnectTimeoutMs(1500);
        upstream.setReadTimeoutMs(1600);
        upstream.setWriteTimeoutMs(1700);
        route.setUpstream(upstream);

        properties.setRoutes(List.of(route));

        final GatewayServerConfigFactory factory = new GatewayServerConfigFactory();
        final GatewayServerConfig config = factory.create(properties);

        assertEquals(18080, config.port());
        assertEquals(2048, config.maxContentLength());
        assertFalse(config.pooledAllocatorEnabled());
        assertEquals(1, config.routes().size());
        assertEquals("route-1", config.routes().get(0).routeId());
        assertEquals("/api/ping", config.routes().get(0).path());
        assertEquals(9002, config.routes().get(0).upstream().port());
    }

    @Test
    void shouldThrowWhenRouteIdIsBlank() {
        final GatewayServerProperties properties = new GatewayServerProperties();
        final GatewayServerProperties.RouteProperties route =
                new GatewayServerProperties.RouteProperties();
        route.setRouteId(" ");
        properties.setRoutes(List.of(route));

        final GatewayServerConfigFactory factory = new GatewayServerConfigFactory();
        assertThrows(IllegalArgumentException.class, () -> factory.create(properties));
    }
}
