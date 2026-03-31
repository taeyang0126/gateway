package com.lei.gateway.core.plugin;

import com.lei.gateway.core.config.PluginConfigEntry;
import com.lei.gateway.core.config.Route;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpRequest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 网关插件处理器，替代 GatewaySecurityProcessor，在 RoutingHandler 中调用。
 */
public class GatewayPluginProcessor {

    private static final Logger log = LoggerFactory.getLogger(GatewayPluginProcessor.class);

    private final PluginRegistry pluginRegistry;
    private final PluginConfigResolver configResolver;
    private final PluginChain pluginChain;
    private final List<PluginConfigEntry> globalPluginConfigs;

    /**
     * 创建网关插件处理器。
     */
    public GatewayPluginProcessor(PluginRegistry pluginRegistry,
            PluginConfigResolver configResolver,
            PluginChain pluginChain,
            List<PluginConfigEntry> globalPluginConfigs) {
        this.pluginRegistry = pluginRegistry;
        this.configResolver = configResolver;
        this.pluginChain = pluginChain;
        this.globalPluginConfigs = globalPluginConfigs != null
                ? globalPluginConfigs : Collections.emptyList();
    }

    /**
     * 执行 REQUEST 阶段插件链。
     *
     * @param ctx     Netty channel 上下文
     * @param request 当前 HTTP 请求
     * @param route   匹配的路由
     * @param traceId 追踪 ID
     * @return 包含 PluginResult 和 PluginContext 的执行结果
     */
    public PluginExecutionResult executeRequestPhase(ChannelHandlerContext ctx,
            HttpRequest request, Route route, String traceId) {
        Map<PluginPhase, List<PluginConfig>> phaseConfigs =
                configResolver.resolve(globalPluginConfigs, route);

        List<PluginConfig> requestConfigs =
                phaseConfigs.getOrDefault(PluginPhase.REQUEST, Collections.emptyList());

        List<Plugin> plugins = new ArrayList<>();
        Map<String, PluginConfig> configMap = new HashMap<>();
        for (PluginConfig pc : requestConfigs) {
            Optional<Plugin> pluginOpt = pluginRegistry.find(pc.getPluginName());
            if (pluginOpt.isEmpty()) {
                log.warn("插件未注册: {}", pc.getPluginName());
                continue;
            }
            plugins.add(pluginOpt.get());
            configMap.put(pc.getPluginName(), pc);
        }

        PluginContext pluginContext = new PluginContext(ctx, request, route, traceId);
        PluginResult result = pluginChain.execute(plugins, configMap, pluginContext, route.getId());

        return new PluginExecutionResult(result, pluginContext);
    }
}
