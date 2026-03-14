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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import com.lei.java.gateway.server.config.HostRewriteMode;
import com.lei.java.gateway.server.config.MatchType;
import com.lei.java.gateway.server.config.RouteConfig;
import com.lei.java.gateway.server.config.UpstreamConfig;

class DefaultTimeoutPolicyTests {

    @Test
    void shouldReadTimeoutValuesFromRoute() {
        final TimeoutPolicy policy = new DefaultTimeoutPolicy();
        final RouteConfig route =
                new RouteConfig(
                        "route-1",
                        10,
                        MatchType.PREFIX,
                        "/api/",
                        HostRewriteMode.REWRITE,
                        new UpstreamConfig("http", "127.0.0.1", 9001, 150, 250, 350));

        assertEquals(150, policy.connectTimeoutMs(route));
        assertEquals(250, policy.readTimeoutMs(route));
        assertEquals(350, policy.writeTimeoutMs(route));
    }

    @Test
    void shouldRejectNonPositiveTimeoutValues() {
        final TimeoutPolicy policy = new DefaultTimeoutPolicy();
        final RouteConfig route =
                new RouteConfig(
                        "route-2",
                        10,
                        MatchType.PREFIX,
                        "/api/",
                        HostRewriteMode.REWRITE,
                        new UpstreamConfig("http", "127.0.0.1", 9001, 0, 200, 200));

        assertThrows(IllegalArgumentException.class, () -> policy.connectTimeoutMs(route));
    }
}
