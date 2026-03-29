package com.lei.gateway.core.proxy;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lei.gateway.core.config.GatewayProperties;
import com.lei.gateway.core.config.HealthProperties;
import com.lei.gateway.core.config.Route;
import com.lei.gateway.core.config.RouteResolver;
import io.netty.channel.Channel;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WarmupRunnerTest {

    private RouteResolver routeResolver;
    private GatewayProperties gatewayProperties;
    private UpstreamConnectionPool connectionPool;
    private HealthProperties healthProperties;
    private ObjectMapper objectMapper;
    private WarmupRunner warmupRunner;

    @BeforeEach
    void setUp() {
        routeResolver = mock(RouteResolver.class);
        gatewayProperties = mock(GatewayProperties.class);
        connectionPool = mock(UpstreamConnectionPool.class);
        healthProperties = new HealthProperties();
        healthProperties.setWarmupTimeoutSeconds(10);
        objectMapper = new ObjectMapper();
        warmupRunner = new WarmupRunner(routeResolver, gatewayProperties,
                connectionPool, healthProperties, objectMapper);
    }

    @Test
    void runWarmupWithNoRoutes() {
        when(gatewayProperties.getRoutes()).thenReturn(List.of());

        assertThatCode(() -> warmupRunner.runWarmup()).doesNotThrowAnyException();
        verify(connectionPool, never()).acquire(anyString(), anyInt());
    }

    @Test
    void runWarmupExecutesRouteResolveAndUpstreamAcquire() {
        Route route = new Route();
        route.setId("test");
        route.setPathPrefix("/api/**");
        route.setUpstream("http://localhost:8081");
        when(gatewayProperties.getRoutes()).thenReturn(List.of(route));
        when(routeResolver.resolve("/api/")).thenReturn(Optional.of(route));

        Channel channel = mock(Channel.class);
        when(channel.isActive()).thenReturn(true);
        when(connectionPool.acquire("localhost", 8081))
                .thenReturn(CompletableFuture.completedFuture(channel));

        assertThatCode(() -> warmupRunner.runWarmup()).doesNotThrowAnyException();
        verify(routeResolver, atLeastOnce()).resolve("/api/");
        verify(connectionPool).acquire("localhost", 8081);
        verify(connectionPool).release(channel);
    }

    @Test
    void runWarmupDoesNotBlockWhenUpstreamUnreachable() {
        Route route = new Route();
        route.setId("test");
        route.setPathPrefix("/api/**");
        route.setUpstream("http://unreachable-host:9999");
        when(gatewayProperties.getRoutes()).thenReturn(List.of(route));
        when(routeResolver.resolve("/api/")).thenReturn(Optional.empty());

        CompletableFuture<Channel> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new TimeoutException("connect timeout"));
        when(connectionPool.acquire("unreachable-host", 9999))
                .thenReturn(failedFuture);

        assertThatCode(() -> warmupRunner.runWarmup()).doesNotThrowAnyException();
    }

    @Test
    void runWarmupHandlesInvalidUpstreamUri() {
        Route route = new Route();
        route.setId("bad");
        route.setPathPrefix("/bad/**");
        route.setUpstream("not a valid uri");
        when(gatewayProperties.getRoutes()).thenReturn(List.of(route));

        assertThatCode(() -> warmupRunner.runWarmup()).doesNotThrowAnyException();
    }

    @Test
    void stripWildcardRemovesAntPatterns() {
        assertThatCode(() -> {
            assert "/api/".equals(WarmupRunner.stripWildcard("/api/**"));
            assert "/api/".equals(WarmupRunner.stripWildcard("/api/*"));
            assert "/exact".equals(WarmupRunner.stripWildcard("/exact"));
        }).doesNotThrowAnyException();
    }

    @Test
    void runWarmupHandlesUpstreamWithDefaultPort() {
        Route route = new Route();
        route.setId("default-port");
        route.setPathPrefix("/svc/**");
        route.setUpstream("http://myhost");
        when(gatewayProperties.getRoutes()).thenReturn(List.of(route));
        when(routeResolver.resolve("/svc/")).thenReturn(Optional.of(route));

        Channel channel = mock(Channel.class);
        when(channel.isActive()).thenReturn(true);
        when(connectionPool.acquire("myhost", 80))
                .thenReturn(CompletableFuture.completedFuture(channel));

        assertThatCode(() -> warmupRunner.runWarmup()).doesNotThrowAnyException();
        verify(connectionPool).acquire("myhost", 80);
        verify(connectionPool).release(channel);
    }

    @Test
    void runWarmupDeduplicatesSameUpstreamAcrossRoutes() {
        Route routeA = new Route();
        routeA.setId("a");
        routeA.setPathPrefix("/a/**");
        routeA.setUpstream("http://localhost:8081");

        Route routeB = new Route();
        routeB.setId("b");
        routeB.setPathPrefix("/b/**");
        routeB.setUpstream("http://localhost:8081");

        when(gatewayProperties.getRoutes()).thenReturn(List.of(routeA, routeB));
        when(routeResolver.resolve(anyString())).thenReturn(Optional.of(routeA));

        Channel channel = mock(Channel.class);
        when(channel.isActive()).thenReturn(true);
        when(connectionPool.acquire("localhost", 8081))
                .thenReturn(CompletableFuture.completedFuture(channel));

        assertThatCode(() -> warmupRunner.runWarmup()).doesNotThrowAnyException();
        verify(connectionPool, times(1)).acquire("localhost", 8081);
        verify(connectionPool).release(channel);
    }
}
