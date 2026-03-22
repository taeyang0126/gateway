package com.lei.gateway.core.filter.builtin;

/**
 * 鉴权过滤器配置。
 */
public class AuthConfig {

    private boolean enabled = false;
    private AuthType type = AuthType.JWT;
    private String authServiceUrl;
    private int authServiceTimeoutMs = 3000;
    private int authCacheTtlSeconds = 0;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public AuthType getType() {
        return type;
    }

    public void setType(AuthType type) {
        this.type = type;
    }

    public String getAuthServiceUrl() {
        return authServiceUrl;
    }

    public void setAuthServiceUrl(String authServiceUrl) {
        this.authServiceUrl = authServiceUrl;
    }

    public int getAuthServiceTimeoutMs() {
        return authServiceTimeoutMs;
    }

    public void setAuthServiceTimeoutMs(int authServiceTimeoutMs) {
        this.authServiceTimeoutMs = authServiceTimeoutMs;
    }

    public int getAuthCacheTtlSeconds() {
        return authCacheTtlSeconds;
    }

    public void setAuthCacheTtlSeconds(int authCacheTtlSeconds) {
        this.authCacheTtlSeconds = authCacheTtlSeconds;
    }

    /** 鉴权协议类型。 */
    public enum AuthType {
        JWT
    }
}
