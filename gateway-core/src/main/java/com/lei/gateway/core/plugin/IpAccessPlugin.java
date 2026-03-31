package com.lei.gateway.core.plugin;

import com.lei.gateway.core.security.CidrMatcher;
import io.netty.handler.codec.http.HttpResponseStatus;
import java.util.Collections;
import java.util.List;
import java.util.Map;

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
    public PluginResult execute(PluginContext context, PluginConfig config) {
        Map<String, Object> configMap = config.getConfig();
        if (configMap == null) {
            configMap = Collections.emptyMap();
        }

        boolean enabled = toBoolean(configMap.get("enabled"), true);
        if (!enabled) {
            return PluginResult.doContinue();
        }

        boolean shadow = toBoolean(configMap.get("shadow"), false);
        @SuppressWarnings("unchecked")
        List<String> denyList = (List<String>) configMap.getOrDefault("deny-list", Collections.emptyList());
        @SuppressWarnings("unchecked")
        List<String> allowList = (List<String>) configMap.getOrDefault("allow-list", Collections.emptyList());

        String clientIp = context.getClientIp();

        if (matchesAny(clientIp, denyList)) {
            if (shadow) {
                return PluginResult.doContinue();
            }
            return PluginResult.shortCircuit(HttpResponseStatus.FORBIDDEN,
                    "Forbidden", NAME, "ip_in_deny_list", null);
        }

        if (!allowList.isEmpty() && !matchesAny(clientIp, allowList)) {
            if (shadow) {
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

    private static boolean toBoolean(Object value, boolean defaultValue) {
        if (value instanceof Boolean boolVal) {
            return boolVal;
        }
        if (value instanceof String str) {
            return Boolean.parseBoolean(str);
        }
        return defaultValue;
    }
}
