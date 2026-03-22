package com.lei.gateway.core.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

import com.lei.gateway.core.filter.FilterChainFactory;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

class InvalidConfigTest {

    @Test
    void invalidRouteConfigFailsStartup() {
        try {
            new SpringApplicationBuilder(GatewayAutoConfiguration.class)
                .profiles("invalid")
                .run();
            throw new AssertionError("Expected startup to fail");
        } catch (Exception ex) {
            assertThat(findRootCause(ex).getMessage())
                .containsAnyOf("Binding validation errors", "不能为空",
                    "must not be blank");
        }
    }

    @Test
    void validConfigWithFilters_startsSuccessfully() {
        // 路由配置了 filters 列表，但过滤器 Bean 未注册（空 registry），应正常启动（未知过滤器 warn 跳过）
        try (ConfigurableApplicationContext ctx =
                new SpringApplicationBuilder(GatewayAutoConfiguration.class)
                    .profiles("filter-chain-valid")
                    .run()) {
            FilterChainFactory factory = ctx.getBean(FilterChainFactory.class);
            // 未知过滤器被跳过，链为空
            FilterChainFactory.FilterChain chain = factory.getChain("api-route");
            assertThat(chain.prePostFilters()).isEmpty();
        }
    }

    @Test
    void filterChainFactory_builtWithEmptyRegistry_noException() {
        // 直接单元测试：空 registry + 有过滤器配置的路由，不抛异常
        FilterProperties filterProperties = new FilterProperties();
        filterProperties.setDefaultFilters(List.of("ip-access-control", "auth"));
        FilterChainFactory factory = new FilterChainFactory(filterProperties, Map.of());

        Route route = new Route();
        route.setId("test-route");
        route.setPathPrefix("/test/**");
        route.setUpstream("http://localhost:8080");

        assertThatNoException().isThrownBy(() -> factory.buildChains(List.of(route)));
        assertThat(factory.getChain("test-route").prePostFilters()).isEmpty();
    }

    private Throwable findRootCause(Throwable t) {
        Throwable cause = t;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause;
    }
}
