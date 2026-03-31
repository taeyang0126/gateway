package com.lei.gateway.core.plugin;

import com.lei.gateway.core.config.GatewayProperties;
import com.lei.gateway.core.config.PluginConfigEntry;
import com.lei.gateway.core.config.Route;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.event.EventListener;

/**
 * 启动时校验所有插件配置的绑定正确性。
 *
 * <p>遍历全局插件配置和每条路由的插件配置，尝试绑定到强类型 POJO，
 * 绑定失败则抛出 PluginConfigBindException，阻止网关启动。
 */
public class PluginConfigValidator {

    private static final Logger log = LoggerFactory.getLogger(PluginConfigValidator.class);

    private final GatewayProperties gatewayProperties;
    private final PluginConfigResolver configResolver;

    /**
     * 创建插件配置校验器。
     */
    public PluginConfigValidator(GatewayProperties gatewayProperties,
            PluginConfigResolver configResolver) {
        this.gatewayProperties = gatewayProperties;
        this.configResolver = configResolver;
    }

    /**
     * 在 Spring 容器启动完成后校验所有插件配置。
     */
    @EventListener(ApplicationStartedEvent.class)
    public void validate() {
        log.info("开始校验插件配置...");
        List<PluginConfigEntry> globalPlugins = gatewayProperties.getPlugins();

        for (Route route : gatewayProperties.getRoutes()) {
            configResolver.resolve(globalPlugins, route);
        }
        log.info("插件配置校验通过");
    }
}
