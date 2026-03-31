package com.lei.gateway.core.plugin;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.lei.gateway.core.security.CidrMatcher;
import io.netty.handler.codec.http.HttpResponseStatus;
import java.util.Collections;
import java.util.List;

/**
 * IP 黑白名单访问控制插件。
 *
 * <p>黑名单优先于白名单。shadow 模式下命中规则只记日志不拦截。
 */
public class IpAccessPlugin implements Plugin {

    private static final String NAME = "ip-access";
    private static final int DEFAULT_PRIORITY = 2000;

    private final CidrMatcher cidrMatcher;

    /**
     * 创建 IP 访问控制插件。
     */
    public IpAccessPlugin(CidrMatcher cidrMatcher) {
        this.cidrMatcher = cidrMatcher;
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

        if (!cfg.enabled) {
            return PluginResult.doContinue();
        }

        String clientIp = context.getClientIp();

        if (matchesAny(clientIp, cfg.denyList)) {
            if (cfg.shadow) {
                return PluginResult.doContinue();
            }
            return PluginResult.shortCircuit(HttpResponseStatus.FORBIDDEN,
                    "Forbidden", NAME, "ip_in_deny_list", null);
        }

        if (!cfg.allowList.isEmpty() && !matchesAny(clientIp, cfg.allowList)) {
            if (cfg.shadow) {
                return PluginResult.doContinue();
            }
            return PluginResult.shortCircuit(HttpResponseStatus.FORBIDDEN,
                    "Forbidden", NAME, "ip_not_in_allow_list", null);
        }

        return PluginResult.doContinue();
    }

    private boolean matchesAny(String ip, List<String> rules) {
        for (String rule : rules) {
            if (cidrMatcher.matches(ip, rule)) {
                return true;
            }
        }
        return false;
    }

    /**
     * IpAccess 插件配置。
     */
    public static class Config {

        private boolean enabled = true;
        private boolean shadow = false;

        @JsonProperty("deny-list")
        private List<String> denyList = Collections.emptyList();

        @JsonProperty("allow-list")
        private List<String> allowList = Collections.emptyList();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public boolean isShadow() {
            return shadow;
        }

        public void setShadow(boolean shadow) {
            this.shadow = shadow;
        }

        public List<String> getDenyList() {
            return denyList;
        }

        public void setDenyList(List<String> denyList) {
            this.denyList = denyList;
        }

        public List<String> getAllowList() {
            return allowList;
        }

        public void setAllowList(List<String> allowList) {
            this.allowList = allowList;
        }
    }
}
