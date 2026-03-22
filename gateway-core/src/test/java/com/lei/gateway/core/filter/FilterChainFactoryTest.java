package com.lei.gateway.core.filter;

import static org.assertj.core.api.Assertions.assertThat;

import com.lei.gateway.core.config.FilterProperties;
import com.lei.gateway.core.config.Route;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * FilterChainFactory 单元测试：验证过滤器链构建逻辑。
 */
class FilterChainFactoryTest {

    private FilterProperties filterProperties;

    @BeforeEach
    void setUp() {
        filterProperties = new FilterProperties();
    }

    @Test
    void emptyRegistry_routeWithFilters_unknownFilterSkipped() {
        // 路由配置了未知过滤器名，应跳过（warn 日志），不抛异常
        filterProperties.setDefaultFilters(List.of());
        FilterChainFactory factory = new FilterChainFactory(filterProperties, Map.of());

        Route route = new Route();
        route.setId("test");
        route.setPathPrefix("/test/**");
        route.setUpstream("http://localhost:8080");
        route.setFilters(List.of("unknown-filter"));

        factory.buildChains(List.of(route));

        FilterChainFactory.FilterChain chain = factory.getChain("test");
        assertThat(chain.prePostFilters()).isEmpty();
        assertThat(chain.wrappingFilter()).isNull();
    }

    @Test
    void routeWithNullFilters_usesGlobalDefault() {
        // 路由未配置 filters（null），使用全局默认列表
        Filter mockFilter = mockFilter("mock-filter", 0);
        filterProperties.setDefaultFilters(List.of("mock-filter"));
        FilterChainFactory factory = new FilterChainFactory(
                filterProperties, Map.of("mock-filter", mockFilter));

        Route route = new Route();
        route.setId("test");
        route.setPathPrefix("/test/**");
        route.setUpstream("http://localhost:8080");
        // route.setFilters(null) — 默认就是 null

        factory.buildChains(List.of(route));

        FilterChainFactory.FilterChain chain = factory.getChain("test");
        assertThat(chain.prePostFilters()).hasSize(1);
        assertThat(chain.prePostFilters().get(0).name()).isEqualTo("mock-filter");
    }

    @Test
    void routeWithEmptyFilters_noFiltersApplied() {
        // 路由显式配置空列表，不启用任何过滤器
        Filter mockFilter = mockFilter("mock-filter", 0);
        filterProperties.setDefaultFilters(List.of("mock-filter"));
        FilterChainFactory factory = new FilterChainFactory(
                filterProperties, Map.of("mock-filter", mockFilter));

        Route route = new Route();
        route.setId("test");
        route.setPathPrefix("/test/**");
        route.setUpstream("http://localhost:8080");
        route.setFilters(List.of()); // 显式空列表

        factory.buildChains(List.of(route));

        FilterChainFactory.FilterChain chain = factory.getChain("test");
        assertThat(chain.prePostFilters()).isEmpty();
        assertThat(chain.wrappingFilter()).isNull();
    }

    @Test
    void globalDefaultFilters_sortedByOrder() {
        // 全局默认列表（路由未显式配置），按 getOrder() 升序排列
        Filter filterOrder200 = mockFilter("filter-200", 200);
        Filter filterOrder100 = mockFilter("filter-100", 100);
        Filter filterOrder300 = mockFilter("filter-300", 300);

        filterProperties.setDefaultFilters(
                List.of("filter-200", "filter-100", "filter-300"));
        FilterChainFactory factory = new FilterChainFactory(filterProperties, Map.of(
                "filter-200", filterOrder200,
                "filter-100", filterOrder100,
                "filter-300", filterOrder300));

        Route route = new Route();
        route.setId("test");
        route.setPathPrefix("/test/**");
        route.setUpstream("http://localhost:8080");
        // filters = null，使用全局默认，按 getOrder() 排序

        factory.buildChains(List.of(route));

        FilterChainFactory.FilterChain chain = factory.getChain("test");
        assertThat(chain.prePostFilters()).extracting(Filter::name)
                .containsExactly("filter-100", "filter-200", "filter-300");
    }

    @Test
    void explicitRouteFilters_preserveConfiguredOrder() {
        // 路由显式配置 filters 列表，保持配置顺序，不按 getOrder() 重排
        Filter filterOrder200 = mockFilter("filter-200", 200);
        Filter filterOrder100 = mockFilter("filter-100", 100);

        filterProperties.setDefaultFilters(List.of());
        FilterChainFactory factory = new FilterChainFactory(filterProperties, Map.of(
                "filter-200", filterOrder200,
                "filter-100", filterOrder100));

        Route route = new Route();
        route.setId("test");
        route.setPathPrefix("/test/**");
        route.setUpstream("http://localhost:8080");
        // 显式配置：200 在前，100 在后（与 getOrder() 相反）
        route.setFilters(List.of("filter-200", "filter-100"));

        factory.buildChains(List.of(route));

        FilterChainFactory.FilterChain chain = factory.getChain("test");
        assertThat(chain.prePostFilters()).extracting(Filter::name)
                .containsExactly("filter-200", "filter-100");
    }

    @Test
    void wrappingFilter_extractedSeparately() {
        // WrappingFilter（如 RetryFilter）不参与 pre/post 链，单独提取
        Filter normalFilter = mockFilter("normal", 100);
        WrappingFilter wrapping = mockWrappingFilter("retry");

        filterProperties.setDefaultFilters(List.of("normal", "retry"));
        FilterChainFactory factory = new FilterChainFactory(filterProperties, Map.of(
                "normal", normalFilter,
                "retry", wrapping));

        Route route = new Route();
        route.setId("test");
        route.setPathPrefix("/test/**");
        route.setUpstream("http://localhost:8080");

        factory.buildChains(List.of(route));

        FilterChainFactory.FilterChain chain = factory.getChain("test");
        assertThat(chain.prePostFilters()).hasSize(1);
        assertThat(chain.prePostFilters().get(0).name()).isEqualTo("normal");
        assertThat(chain.wrappingFilter()).isNotNull();
        assertThat(chain.wrappingFilter().name()).isEqualTo("retry");
        assertThat(chain.hasWrappingFilter()).isTrue();
    }

    @Test
    void multipleWrappingFilters_onlyFirstUsed() {
        // 路由配置了多个 WrappingFilter，只使用第一个，warn 日志
        WrappingFilter retry1 = mockWrappingFilter("retry-1");
        WrappingFilter retry2 = mockWrappingFilter("retry-2");

        filterProperties.setDefaultFilters(List.of());
        FilterChainFactory factory = new FilterChainFactory(filterProperties, Map.of(
                "retry-1", retry1,
                "retry-2", retry2));

        Route route = new Route();
        route.setId("test");
        route.setPathPrefix("/test/**");
        route.setUpstream("http://localhost:8080");
        route.setFilters(List.of("retry-1", "retry-2"));

        factory.buildChains(List.of(route));

        FilterChainFactory.FilterChain chain = factory.getChain("test");
        assertThat(chain.prePostFilters()).isEmpty();
        assertThat(chain.wrappingFilter()).isNotNull();
        assertThat(chain.wrappingFilter().name()).isEqualTo("retry-1");
    }

    @Test
    void unknownRouteId_returnsEmptyChain() {
        FilterChainFactory factory = new FilterChainFactory(filterProperties, Map.of());
        factory.buildChains(List.of());

        FilterChainFactory.FilterChain chain = factory.getChain("non-existent");
        assertThat(chain.prePostFilters()).isEmpty();
        assertThat(chain.wrappingFilter()).isNull();
        assertThat(chain.hasWrappingFilter()).isFalse();
    }

    @Test
    void multipleRoutes_eachGetOwnChain() {
        Filter filterA = mockFilter("filter-a", 0);
        Filter filterB = mockFilter("filter-b", 0);

        filterProperties.setDefaultFilters(List.of());
        FilterChainFactory factory = new FilterChainFactory(filterProperties, Map.of(
                "filter-a", filterA,
                "filter-b", filterB));

        Route routeA = new Route();
        routeA.setId("route-a");
        routeA.setPathPrefix("/a/**");
        routeA.setUpstream("http://localhost:8080");
        routeA.setFilters(List.of("filter-a"));

        Route routeB = new Route();
        routeB.setId("route-b");
        routeB.setPathPrefix("/b/**");
        routeB.setUpstream("http://localhost:8081");
        routeB.setFilters(List.of("filter-b"));

        factory.buildChains(List.of(routeA, routeB));

        assertThat(factory.getChain("route-a").prePostFilters())
                .extracting(Filter::name).containsExactly("filter-a");
        assertThat(factory.getChain("route-b").prePostFilters())
                .extracting(Filter::name).containsExactly("filter-b");
    }

    // ---- 辅助方法 ----

    private static Filter mockFilter(String name, int order) {
        return new Filter() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public int getOrder() {
                return order;
            }

            @Override
            public CompletableFuture<FilterResult> pre(
                    io.netty.channel.ChannelHandlerContext ctx,
                    io.netty.handler.codec.http.HttpRequest request,
                    FilterContext context) {
                return CompletableFuture.completedFuture(FilterResult.CONTINUE);
            }
        };
    }

    private static WrappingFilter mockWrappingFilter(String name) {
        return new WrappingFilter() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public CompletableFuture<FilterResult> pre(
                    io.netty.channel.ChannelHandlerContext ctx,
                    io.netty.handler.codec.http.HttpRequest request,
                    FilterContext context) {
                return CompletableFuture.completedFuture(FilterResult.CONTINUE);
            }

            @Override
            public CompletableFuture<Void> executeWithProxy(
                    io.netty.channel.ChannelHandlerContext ctx,
                    io.netty.handler.codec.http.HttpRequest request,
                    FilterContext context,
                    ProxyInvoker proxyInvoker) {
                return CompletableFuture.completedFuture(null);
            }
        };
    }
}
