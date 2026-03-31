package com.lei.gateway.config;

import java.util.Comparator;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;

/**
 * 路由匹配器，支持 Ant 风格路径匹配。
 *
 * <p>多条路由匹配时，优先按 priority 升序（数字越小越优先），
 * priority 相同时按 pathPrefix 长度降序（最精确匹配优先）。
 */
@Component
public class RouteResolver {

    private static final AntPathMatcher ANT_MATCHER = new AntPathMatcher();

    private final GatewayProperties gatewayProperties;

    /**
     * 构造路由匹配器。
     *
     * @param gatewayProperties 网关配置
     */
    public RouteResolver(GatewayProperties gatewayProperties) {
        this.gatewayProperties = gatewayProperties;
    }

    /**
     * 根据请求路径匹配路由，支持 Ant 风格 pattern（*, **, ?）。
     *
     * @param path 请求路径（不含 query string）
     * @return 匹配的路由，未匹配返回空
     */
    public Optional<Route> resolve(String path) {
        if (path == null) {
            return Optional.empty();
        }
        return gatewayProperties.getRoutes().stream()
                .filter(route -> ANT_MATCHER.match(route.getPathPrefix(), path))
                .min(Comparator
                        .comparingInt(Route::getPriority)
                        .thenComparingInt(route -> -route.getPathPrefix().length()));
    }
}
