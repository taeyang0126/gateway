package com.lei.gateway.core.security;

import com.lei.gateway.core.config.SecurityProperties;

/**
 * 认证提供器 SPI。
 */
public interface AuthProvider {

    /**
     * 认证类型名称。
     */
    SecurityProperties.AuthType type();

    /**
     * 执行认证。
     */
    AuthenticationResult authenticate(SecurityRequestContext context,
            EffectiveSecurityConfig.Auth authConfig);
}
