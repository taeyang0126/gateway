package com.lei.gateway.core.plugin;

/**
 * 插件链执行结果，包含 PluginResult 和 PluginContext。
 * RoutingHandler 需要 PluginContext 来提取 clientIp、traceTags 等写入 Channel Attribute。
 */
public class PluginExecutionResult {

    private final PluginResult result;
    private final PluginContext context;

    /**
     * 创建插件链执行结果。
     */
    public PluginExecutionResult(PluginResult result, PluginContext context) {
        this.result = result;
        this.context = context;
    }

    /**
     * 获取插件执行结果。
     */
    public PluginResult getResult() {
        return result;
    }

    /**
     * 获取插件执行上下文。
     */
    public PluginContext getContext() {
        return context;
    }

    /**
     * 是否继续执行（委托给 PluginResult）。
     */
    public boolean isContinue() {
        return result.isContinue();
    }
}
