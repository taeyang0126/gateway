package com.lei.gateway.core.filter;

import com.lei.gateway.core.config.FilterProperties;
import com.lei.gateway.core.config.Route;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 根据 Route 配置构建并缓存过滤器链实例。
 *
 * <p>有状态过滤器（CircuitBreakerFilter、RateLimitFilter）与 Route 绑定，
 * 启动时预构建并缓存，每次请求直接复用。
 */
public class FilterChainFactory {

    private static final Logger log = LoggerFactory.getLogger(FilterChainFactory.class);

    /**
     * 过滤器链：pre/post 过滤器列表 + 可选的 WrappingFilter（如 RetryFilter）。
     *
     * @param prePostFilters 参与 pre/post 链的过滤器列表
     * @param wrappingFilter 可选的 WrappingFilter，null 表示无
     */
    public record FilterChain(List<Filter> prePostFilters, WrappingFilter wrappingFilter) {

        /** 是否存在 WrappingFilter。 */
        public boolean hasWrappingFilter() {
            return wrappingFilter != null;
        }
    }

    private final FilterProperties filterProperties;
    /** 所有已注册的过滤器实例，key 为过滤器名称（小写连字符，如 "ip-access-control"）。 */
    private final Map<String, Filter> filterRegistry;
    /** 路由级过滤器链缓存，key 为 routeId。 */
    private final Map<String, FilterChain> routeFilterChains = new ConcurrentHashMap<>();

    /**
     * 创建 FilterChainFactory。
     *
     * @param filterProperties 过滤器链全局配置
     * @param filterRegistry   已注册的过滤器实例，key 为过滤器名称
     */
    public FilterChainFactory(FilterProperties filterProperties,
            Map<String, Filter> filterRegistry) {
        this.filterProperties = filterProperties;
        this.filterRegistry = filterRegistry;
    }

    /**
     * 为所有路由预构建过滤器链并缓存。
     * 在 GatewayAutoConfiguration.@PostConstruct 中调用。
     */
    public void buildChains(List<Route> routes) {
        for (Route route : routes) {
            FilterChain chain = buildChain(route);
            routeFilterChains.put(route.getId(), chain);
            log.debug("路由 [{}] 过滤器链构建完成，prePostFilters={}, wrappingFilter={}",
                    route.getId(),
                    chain.prePostFilters().stream().map(Filter::name).toList(),
                    chain.wrappingFilter() != null ? chain.wrappingFilter().name() : "none");
        }
    }

    /**
     * 获取路由对应的过滤器链（返回缓存实例）。
     * 若路由未配置任何过滤器，返回空链。
     *
     * @param routeId 路由 ID
     * @return 过滤器链
     */
    public FilterChain getChain(String routeId) {
        return routeFilterChains.getOrDefault(routeId,
                new FilterChain(List.of(), null));
    }

    private FilterChain buildChain(Route route) {
        // 路由级 filters 列表优先；null 表示使用全局默认；空列表表示不启用
        List<String> filterNames = route.getFilters() != null
                ? route.getFilters()
                : filterProperties.getDefaultFilters();

        if (filterNames.isEmpty()) {
            return new FilterChain(List.of(), null);
        }

        List<Filter> resolved = new ArrayList<>();
        for (String name : filterNames) {
            Filter f = filterRegistry.get(name);
            if (f == null) {
                log.warn("路由 [{}] 配置了未知过滤器: {}，已跳过", route.getId(), name);
                continue;
            }
            resolved.add(f);
        }

        // 若路由未显式配置顺序（即使用全局默认列表），按 getOrder() 升序排列
        // 若路由显式配置了 filters 列表，保持配置顺序，不重排
        boolean useExplicitOrder = route.getFilters() != null;
        if (!useExplicitOrder) {
            resolved.sort(Comparator.comparingInt(f -> {
                if (f instanceof WrappingFilter) {
                    return Integer.MAX_VALUE; // WrappingFilter 排到最后再单独提取
                }
                return f.getOrder();
            }));
        }

        // 提取 WrappingFilter（RetryFilter），不参与 pre/post 链
        WrappingFilter wrappingFilter = null;
        List<Filter> prePostFilters = new ArrayList<>();
        for (Filter f : resolved) {
            if (f instanceof WrappingFilter wf) {
                if (wrappingFilter != null) {
                    log.warn("路由 [{}] 配置了多个 WrappingFilter，仅使用第一个: {}",
                            route.getId(), wrappingFilter.name());
                } else {
                    wrappingFilter = wf;
                }
            } else {
                prePostFilters.add(f);
            }
        }

        return new FilterChain(List.copyOf(prePostFilters), wrappingFilter);
    }
}
