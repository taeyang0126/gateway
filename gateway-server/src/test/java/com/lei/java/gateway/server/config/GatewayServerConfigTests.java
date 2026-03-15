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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class GatewayServerConfigTests {

    @Test
    void shouldEnablePooledAllocatorWhenUseDefaultConfig() {
        final GatewayServerConfig config = GatewayServerConfig.defaultConfig();

        assertEquals(8080, config.port());
        assertEquals(5 * 1024 * 1024, config.maxContentLength());
        assertTrue(config.pooledAllocatorEnabled());
        assertTrue(config.healthEndpointEnabled());
        assertTrue(config.metricsEndpointEnabled());
        assertFalse(config.managementAllowedClientIps().isEmpty());
        assertTrue(config.maxPendingPerRoute() > 0);
        assertTrue(config.routes().isEmpty());
    }

    @Test
    void shouldRejectWhenMaxPendingPerRouteIsNonPositive() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new GatewayServerConfig(
                                8080, 1024 * 1024, true, List.of(), true, true, List.of("*"), 0));
    }
}
