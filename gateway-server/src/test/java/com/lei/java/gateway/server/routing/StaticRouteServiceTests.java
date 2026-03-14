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
package com.lei.java.gateway.server.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.lei.java.gateway.server.config.HostRewriteMode;
import com.lei.java.gateway.server.config.MatchType;
import com.lei.java.gateway.server.config.RouteConfig;
import com.lei.java.gateway.server.config.UpstreamConfig;

class StaticRouteServiceTests {

    private static final UpstreamConfig DEFAULT_UPSTREAM =
            new UpstreamConfig("http", "127.0.0.1", 9001, 1000, 1000, 1000);

    @Test
    void shouldSelectExactRouteWhenExactAndPrefixBothMatched() {
        final StaticRouteService routeService = new StaticRouteService();
        final List<RouteConfig> routes =
                List.of(
                        route("prefix-api", 10, MatchType.PREFIX, "/api/"),
                        route("exact-ping", 10, MatchType.EXACT, "/api/ping"));

        final Optional<RouteConfig> selected = routeService.select("/api/ping", routes);

        assertTrue(selected.isPresent());
        assertEquals("exact-ping", selected.get().routeId());
    }

    @Test
    void shouldSelectLongestPrefixRouteWhenMultiplePrefixMatched() {
        final StaticRouteService routeService = new StaticRouteService();
        final List<RouteConfig> routes =
                List.of(
                        route("short-prefix", 10, MatchType.PREFIX, "/api/"),
                        route("long-prefix", 10, MatchType.PREFIX, "/api/order/"));

        final Optional<RouteConfig> selected = routeService.select("/api/order/123", routes);

        assertTrue(selected.isPresent());
        assertEquals("long-prefix", selected.get().routeId());
    }

    @Test
    void shouldSelectHigherPriorityRouteWhenMatchedRoutesHaveDifferentPriority() {
        final StaticRouteService routeService = new StaticRouteService();
        final List<RouteConfig> routes =
                List.of(
                        route("low-priority", 10, MatchType.PREFIX, "/api/"),
                        route("high-priority", 100, MatchType.PREFIX, "/api/"));

        final Optional<RouteConfig> selected = routeService.select("/api/test", routes);

        assertTrue(selected.isPresent());
        assertEquals("high-priority", selected.get().routeId());
    }

    @Test
    void shouldSelectFirstDeclaredRouteWhenAllRankingFieldsAreEqual() {
        final StaticRouteService routeService = new StaticRouteService();
        final List<RouteConfig> routes =
                List.of(
                        route("first", 10, MatchType.PREFIX, "/api/"),
                        route("second", 10, MatchType.PREFIX, "/api/"));

        final Optional<RouteConfig> selected = routeService.select("/api/anything", routes);

        assertTrue(selected.isPresent());
        assertEquals("first", selected.get().routeId());
    }

    @Test
    void shouldReturnEmptyWhenNoRouteMatched() {
        final StaticRouteService routeService = new StaticRouteService();
        final List<RouteConfig> routes = List.of(route("api", 10, MatchType.PREFIX, "/api/"));

        final Optional<RouteConfig> selected = routeService.select("/metrics", routes);

        assertTrue(selected.isEmpty());
    }

    private static RouteConfig route(
            final String routeId,
            final int priority,
            final MatchType matchType,
            final String path) {
        return new RouteConfig(
                routeId, priority, matchType, path, HostRewriteMode.REWRITE, DEFAULT_UPSTREAM);
    }
}
