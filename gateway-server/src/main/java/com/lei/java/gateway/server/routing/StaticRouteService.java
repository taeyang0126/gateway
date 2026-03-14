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

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.IntStream;

import com.lei.java.gateway.server.config.MatchType;
import com.lei.java.gateway.server.config.RouteConfig;

/** 阶段 1 静态路由实现。 */
public final class StaticRouteService implements RouteService {

    private static final Comparator<Candidate> ROUTE_COMPARATOR =
            Comparator.comparingInt((Candidate candidate) -> candidate.route().priority())
                    .reversed()
                    .thenComparingInt(candidate -> matchTypeOrder(candidate.route().matchType()))
                    .thenComparing(
                            Comparator.comparingInt(
                                            (Candidate candidate) ->
                                                    prefixLength(candidate.route()))
                                    .reversed())
                    .thenComparingInt(Candidate::index);

    /**
     * 依据固定规则选择路由：priority -> EXACT -> PREFIX 最长前缀 -> 声明顺序。
     *
     * @param requestPath 请求路径
     * @param routes 路由列表
     * @return 命中的路由，未命中时返回空
     */
    @Override
    public Optional<RouteConfig> select(final String requestPath, final List<RouteConfig> routes) {
        Objects.requireNonNull(requestPath, "requestPath must not be null");
        Objects.requireNonNull(routes, "routes must not be null");

        return IntStream.range(0, routes.size())
                .mapToObj(index -> new Candidate(routes.get(index), index))
                .filter(candidate -> isMatched(requestPath, candidate.route()))
                .min(ROUTE_COMPARATOR)
                .map(Candidate::route);
    }

    private static boolean isMatched(final String requestPath, final RouteConfig route) {
        if (route.matchType() == MatchType.EXACT) {
            return requestPath.equals(route.path());
        }
        return requestPath.startsWith(route.path());
    }

    private static int matchTypeOrder(final MatchType matchType) {
        if (matchType == MatchType.EXACT) {
            return 0;
        }
        return 1;
    }

    private static int prefixLength(final RouteConfig route) {
        if (route.matchType() != MatchType.PREFIX) {
            return Integer.MIN_VALUE;
        }
        return route.path().length();
    }

    private static final class Candidate {
        private final RouteConfig route;
        private final int index;

        private Candidate(final RouteConfig route, final int index) {
            this.route = route;
            this.index = index;
        }

        private RouteConfig route() {
            return route;
        }

        private int index() {
            return index;
        }
    }
}
