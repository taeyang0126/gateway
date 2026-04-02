package com.lei.gateway.plugin;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.lei.gateway.proxy.PathRewriter;

/**
 * 路径重写插件，基于正则表达式对请求 URI 做路径替换。
 *
 * <p>替换字符串支持数字捕获组引用（{@code $1}、{@code $2}）。
 * 不支持命名捕获组引用（{@code ${name}}），因为 YAML 中会被 Spring 当 placeholder 解析。
 */
public class RewritePathPlugin implements Plugin {

    private static final String NAME = "rewrite-path";
    private static final int DEFAULT_PRIORITY = 6200;

    /** PluginContext attribute key，存储重写后的 URI。 */
    public static final String REWRITTEN_URI_KEY = "rewritePath.uri";

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
    public PluginResult execute(PluginContext context,
            PluginConfig pluginConfig) {
        Config cfg = pluginConfig.getTypedConfig(Config.class);
        String uri = context.getRequest().uri();
        String rewritten = PathRewriter.rewrite(uri, cfg.pattern,
                cfg.replacement);
        if (!rewritten.equals(uri)) {
            context.setAttribute(REWRITTEN_URI_KEY, rewritten);
        }
        return PluginResult.doContinue();
    }

    /**
     * RewritePath 插件配置。
     */
    public static class Config {

        @JsonProperty("pattern")
        private String pattern;

        @JsonProperty("replacement")
        private String replacement;

        public String getPattern() {
            return pattern;
        }

        public void setPattern(String pattern) {
            this.pattern = pattern;
        }

        public String getReplacement() {
            return replacement;
        }

        public void setReplacement(String replacement) {
            this.replacement = replacement;
        }
    }
}
