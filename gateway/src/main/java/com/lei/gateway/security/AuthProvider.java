package com.lei.gateway.security;

import com.lei.gateway.config.SecurityProperties;
import io.netty.handler.codec.http.HttpRequest;

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
    AuthenticationResult authenticate(HttpRequest request,
            EffectiveSecurityConfig.Auth authConfig);
}
