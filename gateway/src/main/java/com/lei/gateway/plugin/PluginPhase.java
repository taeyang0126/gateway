package com.lei.gateway.plugin;

/**
 * 插件执行阶段枚举。
 */
public enum PluginPhase {
    /** 路由匹配后、转发前。 */
    REQUEST,
    /** 选择上游节点时。 */
    PROXY,
    /** 收到上游响应时。 */
    RESPONSE,
    /** 上游连接失败或超时时。 */
    ERROR
}
