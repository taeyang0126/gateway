package com.example.gateway.core.config;

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

    @Test
    void matchesByPathPrefix() {
        RouteResolver resolver = createResolver(List.of(
            route("svc", "/api/example", "http://localhost:8081")
        ));

        Optional<Route> result = resolver.resolve("/api/example/hello");
        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo("svc");
        assertThat(result.get().getUpstream()).isEqualTo("http://localhost:8081");
    }

    @Test
    void exactPrefixMatch() {
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
            route("svc", "/api/example", "http://localhost:8081")
        ));

        Optional<Route> result = resolver.resolve("/api/other/hello");
        assertThat(result).isEmpty();
    }

    @Test
    void nullPathReturnsEmpty() {
        RouteResolver resolver = createResolver(List.of(
            route("svc", "/api/example", "http://localhost:8081")
        ));

        Optional<Route> result = resolver.resolve(null);
        assertThat(result).isEmpty();
    }

    @Test
    void longestPrefixWins() {
        RouteResolver resolver = createResolver(List.of(
            route("short", "/api", "http://localhost:8081"),
            route("long", "/api/example", "http://localhost:8082")
        ));

        Optional<Route> result = resolver.resolve("/api/example/hello");
        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo("long");
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
            route("user", "/api/user", "http://localhost:8081"),
            route("order", "/api/order", "http://localhost:8082")
        ));

        assertThat(resolver.resolve("/api/user/list").get().getId()).isEqualTo("user");
        assertThat(resolver.resolve("/api/order/detail").get().getId()).isEqualTo("order");
    }
}
