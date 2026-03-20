package com.example.gateway.core.config;

import java.util.Comparator;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * 路由匹配器，根据请求路径前缀匹配路由规则。
 *
 * <p>多条路由匹配时选择 pathPrefix 最长的（最精确匹配）。
 */
@Component
public class RouteResolver {

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
     * 根据请求路径前缀匹配路由。
     *
     * @param path 请求路径
     * @return 匹配的路由，未匹配返回空
     */
    public Optional<Route> resolve(String path) {
        if (path == null) {
            return Optional.empty();
        }
        return gatewayProperties.getRoutes().stream()
            .filter(route -> path.startsWith(route.getPathPrefix()))
            .max(Comparator.comparingInt(route -> route.getPathPrefix().length()));
    }
}
