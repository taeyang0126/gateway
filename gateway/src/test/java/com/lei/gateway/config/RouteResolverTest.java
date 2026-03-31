package com.lei.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RouteResolverTest {

    private RouteResolver createResolver(List<Route> routes) {
        GatewayProperties props = new GatewayProperties();
        props.setRoutes(routes);
        return new RouteResolver(props);
    }

    private Route route(String id, String pathPrefix, String upstream) {
        Route r = new Route();
        r.setId(id);
        r.setPathPrefix(pathPrefix);
        r.setUpstream(upstream);
        return r;
    }

    private Route route(String id, String pathPrefix, String upstream, int priority) {
        Route r = route(id, pathPrefix, upstream);
        r.setPriority(priority);
        return r;
    }

    @Test
    void matchesAntWildcard() {
        RouteResolver resolver = createResolver(List.of(
            route("svc", "/api/example/**", "http://localhost:8081")
        ));

        Optional<Route> result = resolver.resolve("/api/example/hello");
        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo("svc");
    }

    @Test
    void exactPatternMatch() {
        RouteResolver resolver = createResolver(List.of(
            route("svc", "/api/example", "http://localhost:8081")
        ));

        Optional<Route> result = resolver.resolve("/api/example");
        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo("svc");
    }

    @Test
    void noMatchReturnsEmpty() {
        RouteResolver resolver = createResolver(List.of(
            route("svc", "/api/example/**", "http://localhost:8081")
        ));

        Optional<Route> result = resolver.resolve("/api/other/hello");
        assertThat(result).isEmpty();
    }

    @Test
    void nullPathReturnsEmpty() {
        RouteResolver resolver = createResolver(List.of(
            route("svc", "/api/example/**", "http://localhost:8081")
        ));

        Optional<Route> result = resolver.resolve(null);
        assertThat(result).isEmpty();
    }

    @Test
    void longestPatternWinsWhenSamePriority() {
        RouteResolver resolver = createResolver(List.of(
            route("short", "/api/**", "http://localhost:8081"),
            route("long", "/api/example/**", "http://localhost:8082")
        ));

        Optional<Route> result = resolver.resolve("/api/example/hello");
        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo("long");
    }

    @Test
    void priorityWinsOverLongerPattern() {
        RouteResolver resolver = createResolver(List.of(
            route("high", "/api/**", "http://localhost:8081", 0),
            route("low", "/api/example/**", "http://localhost:8082", 1)
        ));

        // priority=0 的 /api/** 优先级更高，即使 /api/example/** 更长
        Optional<Route> result = resolver.resolve("/api/example/hello");
        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo("high");
    }

    @Test
    void emptyRoutesReturnsEmpty() {
        RouteResolver resolver = createResolver(List.of());

        Optional<Route> result = resolver.resolve("/api/example");
        assertThat(result).isEmpty();
    }

    @Test
    void multipleRoutesMatchesCorrectOne() {
        RouteResolver resolver = createResolver(List.of(
            route("user", "/api/user/**", "http://localhost:8081"),
            route("order", "/api/order/**", "http://localhost:8082")
        ));

        assertThat(resolver.resolve("/api/user/list").get().getId()).isEqualTo("user");
        assertThat(resolver.resolve("/api/order/detail").get().getId()).isEqualTo("order");
    }

    @Test
    void singleSegmentWildcard() {
        RouteResolver resolver = createResolver(List.of(
            route("svc", "/api/*/profile", "http://localhost:8081")
        ));

        assertThat(resolver.resolve("/api/user/profile")).isPresent();
        assertThat(resolver.resolve("/api/user/other")).isEmpty();
    }
}
