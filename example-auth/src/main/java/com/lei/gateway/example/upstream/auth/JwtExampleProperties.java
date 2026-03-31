package com.lei.gateway.example.upstream.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 示例 JWT 配置，绑定 {@code auth.jwt} 前缀。
 */
@ConfigurationProperties(prefix = "auth.jwt")
public class JwtExampleProperties {

    private String issuer = "example-issuer";
    private String audience = "example-audience";
    private String keyId = "auth-jwt-example-key";
    private String privateKey;
    private String publicKey;
    private String jwksUrl;
    private int jwksRefreshSeconds = 300;
    private int jwksTimeoutMillis = 1000;
    private int tokenExpiresSeconds = 3600;

    public String getIssuer() {
        return issuer;
    }

    public void setIssuer(String issuer) {
        this.issuer = issuer;
    }

    public String getAudience() {
        return audience;
    }

    public void setAudience(String audience) {
        this.audience = audience;
    }

    public String getKeyId() {
        return keyId;
    }

    public void setKeyId(String keyId) {
        this.keyId = keyId;
    }

    public String getPrivateKey() {
        return privateKey;
    }

    public void setPrivateKey(String privateKey) {
        this.privateKey = privateKey;
    }

    public String getPublicKey() {
        return publicKey;
    }

    public void setPublicKey(String publicKey) {
        this.publicKey = publicKey;
    }

    public String getJwksUrl() {
        return jwksUrl;
    }

    public void setJwksUrl(String jwksUrl) {
        this.jwksUrl = jwksUrl;
    }

    public int getJwksRefreshSeconds() {
        return jwksRefreshSeconds;
    }

    public void setJwksRefreshSeconds(int jwksRefreshSeconds) {
        this.jwksRefreshSeconds = jwksRefreshSeconds;
    }

    public int getJwksTimeoutMillis() {
        return jwksTimeoutMillis;
    }

    public void setJwksTimeoutMillis(int jwksTimeoutMillis) {
        this.jwksTimeoutMillis = jwksTimeoutMillis;
    }

    public int getTokenExpiresSeconds() {
        return tokenExpiresSeconds;
    }

    public void setTokenExpiresSeconds(int tokenExpiresSeconds) {
        this.tokenExpiresSeconds = tokenExpiresSeconds;
    }
}
