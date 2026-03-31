package com.lei.gateway.core.plugin;

import com.lei.gateway.core.security.ClientIpResolver;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 真实客户端 IP 解析插件。
 *
 * <p>从 X-Forwarded-For / Forwarded 头解析客户端真实 IP，写入 PluginContext.clientIp。
 */
public class RealIpPlugin implements Plugin {

    private static final String NAME = "real-ip";
    private static final int DEFAULT_PRIORITY = 1000;

    private final ClientIpResolver clientIpResolver;

    /**
     * 创建真实 IP 解析插件。
     */
    public RealIpPlugin(ClientIpResolver clientIpResolver) {
        this.clientIpResolver = clientIpResolver;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public PluginPhase phase() {
        return PluginPhase.REQUEST;
    }

    @Override
    public int defaultPriority() {
        return DEFAULT_PRIORITY;
    }

    @Override
    public PluginResult execute(PluginContext context, PluginConfig config) {
        Map<String, Object> configMap = config.getConfig();
        if (configMap == null) {
            configMap = Collections.emptyMap();
        }

        @SuppressWarnings("unchecked")
        List<String> trustedProxies = (List<String>) configMap.get("trusted-proxies");
        Integer trustedProxyHops = toInteger(configMap.get("trusted-proxy-hops"));

        String clientIp = clientIpResolver.resolve(
                context.getChannelHandlerContext(),
                context.getRequest().headers(),
                trustedProxies != null ? trustedProxies : Collections.emptyList(),
                trustedProxyHops);
        context.setClientIp(clientIp);

        return PluginResult.doContinue();
    }

    private static Integer toInteger(Object value) {
        if (value instanceof Integer intVal) {
            return intVal;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String str && !str.isBlank()) {
            return Integer.parseInt(str);
        }
        return null;
    }
}
