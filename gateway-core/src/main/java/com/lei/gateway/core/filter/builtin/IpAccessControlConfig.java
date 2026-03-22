package com.lei.gateway.core.filter.builtin;

import java.util.ArrayList;
import java.util.List;

/**
 * IP 访问控制过滤器配置。
 */
public class IpAccessControlConfig {

    private Mode mode;
    private List<String> rules = new ArrayList<>();

    public Mode getMode() {
        return mode;
    }

    public void setMode(Mode mode) {
        this.mode = mode;
    }

    public List<String> getRules() {
        return rules;
    }

    public void setRules(List<String> rules) {
        this.rules = rules;
    }

    /** IP 访问控制模式。 */
    public enum Mode {
        ALLOWLIST, DENYLIST
    }
}
