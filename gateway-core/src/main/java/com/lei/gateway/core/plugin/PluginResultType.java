package com.lei.gateway.core.plugin;

/**
 * 插件执行结果类型。
 */
public enum PluginResultType {
    /** 继续执行下一个插件。 */
    CONTINUE,
    /** 短路终止，返回指定 HTTP 响应。 */
    SHORT_CIRCUIT,
    /** 执行异常，返回 500。 */
    ERROR
}
