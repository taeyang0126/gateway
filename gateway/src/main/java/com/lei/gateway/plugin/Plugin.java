package com.lei.gateway.plugin;

/**
 * 网关插件接口，所有扩展逻辑遵循此契约。
 */
public interface Plugin {

    /**
     * 插件唯一名称，非空非 blank。
     */
    String name();

    /**
     * 插件所属执行阶段。
     */
    PluginPhase phase();

    /**
     * 默认优先级，数值越小优先级越高。
     */
    int defaultPriority();

    /**
     * 插件配置 POJO 类型，返回 null 表示不需要强类型绑定。
     */
    default Class<?> configType() {
        return null;
    }

    /**
     * 执行插件逻辑。
     */
    PluginResult execute(PluginContext context, PluginConfig config);
}
