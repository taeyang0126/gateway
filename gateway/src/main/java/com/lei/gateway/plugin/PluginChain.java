package com.lei.gateway.plugin;

import com.lei.gateway.observability.MetricsCollector;
import io.netty.handler.codec.http.HttpResponseStatus;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 插件执行链，按优先级顺序执行插件列表。
 */
public class PluginChain {

    private static final Logger log = LoggerFactory.getLogger(PluginChain.class);

    private final MetricsCollector metricsCollector;

    /**
     * 创建插件执行链。
     */
    public PluginChain(MetricsCollector metricsCollector) {
        this.metricsCollector = metricsCollector;
    }

    /**
     * 按优先级顺序执行插件列表。
     *
     * <p>内部为每个插件记录执行耗时和决策指标。
     *
     * @param plugins 已排序的插件列表（priority 升序）
     * @param configs 插件名 → PluginConfig 映射
     * @param context 插件执行上下文
     * @param routeId 路由 ID（用于指标 tag）
     * @return 最终执行结果
     */
    public PluginResult execute(List<Plugin> plugins,
            Map<String, PluginConfig> configs,
            PluginContext context, String routeId) {
        for (Plugin plugin : plugins) {
            String pluginName = plugin.name();
            PluginConfig config = configs.get(pluginName);
            long startNanos = System.nanoTime();
            PluginResult result;
            try {
                result = plugin.execute(context, config);
            } catch (Exception ex) {
                log.error("插件执行异常 plugin={} routeId={}", pluginName, routeId, ex);
                long durationNanos = System.nanoTime() - startNanos;
                metricsCollector.recordPluginDuration(pluginName, routeId, durationNanos);
                metricsCollector.recordPluginDecision(pluginName, "ERROR", routeId);
                context.putTraceTag(pluginName, "ERROR");
                return PluginResult.error(
                        HttpResponseStatus.INTERNAL_SERVER_ERROR,
                        "Internal plugin error");
            }
            long durationNanos = System.nanoTime() - startNanos;
            String decision = result.getType().name();
            metricsCollector.recordPluginDuration(pluginName, routeId, durationNanos);
            metricsCollector.recordPluginDecision(pluginName, decision, routeId);
            context.putTraceTag(pluginName, decision);

            if (!result.isContinue()) {
                return result;
            }
        }
        return PluginResult.doContinue();
    }
}
