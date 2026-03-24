package com.lei.gateway.core.security;

/**
 * 认证结果。
 */
public class AuthenticationResult {

    private final boolean authenticated;
    private final String userId;
    private final String reason;

    private AuthenticationResult(boolean authenticated, String userId,
            String reason) {
        this.authenticated = authenticated;
        this.userId = userId;
        this.reason = reason;
    }

    /**
     * 生成认证成功结果。
     */
    public static AuthenticationResult success(String userId) {
        return new AuthenticationResult(true, userId, "authenticated");
    }

    /**
     * 生成认证失败结果。
     */
    public static AuthenticationResult failed(String reason) {
        return new AuthenticationResult(false, null, reason);
    }

    public boolean isAuthenticated() {
        return authenticated;
    }

    public String getUserId() {
        return userId;
    }

    public String getReason() {
        return reason;
    }
}
