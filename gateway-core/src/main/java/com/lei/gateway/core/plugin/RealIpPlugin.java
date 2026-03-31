package com.lei.gateway.core.plugin;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.lei.gateway.core.security.ClientIpResolver;
import java.util.Collections;
import java.util.List;

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
    public Class<?> configType() {
        return Config.class;
    }

    @Override
    public PluginResult execute(PluginContext context, PluginConfig pluginConfig) {
        Config cfg = pluginConfig.getTypedConfig(Config.class);

        List<String> proxies = cfg.trustedProxies != null ? cfg.trustedProxies : Collections.emptyList();
        String clientIp = clientIpResolver.resolve(
                context.getChannelHandlerContext(),
                context.getRequest().headers(),
                proxies,
                cfg.trustedProxyHops);
        context.setClientIp(clientIp);

        return PluginResult.doContinue();
    }

    /**
     * RealIp 插件配置。
     */
    public static class Config {

        @JsonProperty("trusted-proxies")
        private List<String> trustedProxies;

        @JsonProperty("trusted-proxy-hops")
        private Integer trustedProxyHops;

        public List<String> getTrustedProxies() {
            return trustedProxies;
        }

        public void setTrustedProxies(List<String> trustedProxies) {
            this.trustedProxies = trustedProxies;
        }

        public Integer getTrustedProxyHops() {
            return trustedProxyHops;
        }

        public void setTrustedProxyHops(Integer trustedProxyHops) {
            this.trustedProxyHops = trustedProxyHops;
        }
    }
}
