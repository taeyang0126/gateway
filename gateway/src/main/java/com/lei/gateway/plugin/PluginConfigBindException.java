package com.lei.gateway.plugin;

/**
 * 插件配置绑定失败时抛出的异常。
 */
public class PluginConfigBindException extends RuntimeException {

    private final String pluginName;

    /**
     * 创建插件配置绑定异常。
     */
    public PluginConfigBindException(String pluginName, String message, Throwable cause) {
        super("插件 [" + pluginName + "] 配置绑定失败: " + message, cause);
        this.pluginName = pluginName;
    }

    /**
     * 获取插件名称。
     */
    public String getPluginName() {
        return pluginName;
    }
}
