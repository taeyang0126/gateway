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
package com.lei.java.gateway.server.bootstrap;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.lei.java.gateway.server.config.GatewayServerConfig;

class GatewayBootstrapTests {

    @Test
    void shouldStartAndStopServerWhenUseEphemeralPort() {
        final GatewayBootstrap bootstrap = new GatewayBootstrap();
        final GatewayServerConfig config = new GatewayServerConfig(0, 1024 * 1024, true, List.of());

        try {
            bootstrap.start(config);
            assertTrue(bootstrap.isRunning());
            assertTrue(bootstrap.boundPort() > 0);
        } finally {
            bootstrap.stop();
        }

        assertFalse(bootstrap.isRunning());
    }
}
