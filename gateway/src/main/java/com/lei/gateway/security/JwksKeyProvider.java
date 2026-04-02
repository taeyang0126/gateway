package com.lei.gateway.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JWT 签名验签公钥提供器，支持静态公钥和 JWKS 缓存。
 */
public class JwksKeyProvider {

    private static final Logger log = LoggerFactory.getLogger(JwksKeyProvider.class);

    private final AtomicLong lastRefreshEpochMillis = new AtomicLong(0L);
    private final Map<String, JWK> kidToKey = new ConcurrentHashMap<>();
    private volatile JWK defaultKey;

    /**
     * 创建 JWKS 密钥提供器。
     */
    public JwksKeyProvider() {
        // no-op
    }

    /**
     * 根据 JWT 头选择验签器。
     */
    public JWSVerifier resolveVerifier(JWSHeader header,
            EffectiveSecurityConfig.Jwt jwtProperties) throws Exception {
        JWK key = resolveKey(header, jwtProperties);
        if (key == null) {
            return null;
        }
        if (key instanceof RSAKey rsaKey) {
            return new RSASSAVerifier(rsaKey.toRSAPublicKey());
        }
        if (key instanceof ECKey ecKey) {
            return new ECDSAVerifier(ecKey.toECPublicKey());
        }
        return null;
    }

    private JWK resolveKey(JWSHeader header,
            EffectiveSecurityConfig.Jwt jwtProperties) throws Exception {
        String publicKeyPem = jwtProperties.getPublicKey();
        if (publicKeyPem != null && !publicKeyPem.isBlank()) {
            return fromStaticPublicKey(publicKeyPem);
        }

        String jwksUrl = jwtProperties.getJwksUrl();
        if (jwksUrl == null || jwksUrl.isBlank()) {
            return null;
        }

        maybeRefresh(false, jwtProperties);
        String kid = header.getKeyID();
        if (kid == null || kid.isBlank()) {
            return defaultKey;
        }
        JWK cached = kidToKey.get(kid);
        if (cached != null) {
            return cached;
        }

        // kid 未命中，触发受控刷新（single-thread synchronized）
        maybeRefresh(true, jwtProperties);
        return kidToKey.get(kid);
    }

    private JWK fromStaticPublicKey(String pem)
            throws Exception {
        PublicKey publicKey = parsePublicKey(pem);
        if (publicKey instanceof RSAPublicKey rsaPublicKey) {
            return new RSAKey.Builder(rsaPublicKey).build();
        }
        if (publicKey instanceof ECPublicKey ecPublicKey) {
            return new ECKey.Builder(
                    Curve.forECParameterSpec(
                            ecPublicKey.getParams()),
                    ecPublicKey).build();
        }
        throw new JOSEException("Unsupported public key type");
    }

    private synchronized void maybeRefresh(boolean force,
            EffectiveSecurityConfig.Jwt jwtProperties) throws Exception {
        long now = System.currentTimeMillis();
        long refreshMillis = jwtProperties.getJwksRefreshSeconds() * 1000L;
        long lastRefresh = lastRefreshEpochMillis.get();
        if (!force && now - lastRefresh < refreshMillis && !kidToKey.isEmpty()) {
            return;
        }

        URI uri = URI.create(jwtProperties.getJwksUrl());
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMillis(jwtProperties.getJwksReadTimeoutMillis()))
                .GET()
                .build();
        HttpResponse<String> response = createClient(jwtProperties).send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("Fetch jwks failed, status="
                    + response.statusCode());
        }
        JWKSet jwkSet = JWKSet.parse(response.body());
        List<JWK> keys = jwkSet.getKeys();
        if (keys.isEmpty()) {
            throw new IllegalStateException("No keys in jwks response");
        }

        Map<String, JWK> next = new ConcurrentHashMap<>();
        for (JWK key : keys) {
            if (key.getKeyID() != null) {
                next.put(key.getKeyID(), key);
            }
        }
        this.kidToKey.clear();
        this.kidToKey.putAll(next);
        this.defaultKey = keys.get(0);
        this.lastRefreshEpochMillis.set(now);
        log.debug("JWKS refreshed, keyCount={}", keys.size());
    }

    private static HttpClient createClient(EffectiveSecurityConfig.Jwt jwtProperties) {
        return HttpClient.newBuilder()
                .connectTimeout(
                        Duration.ofMillis(jwtProperties.getJwksConnectTimeoutMillis()))
                .build();
    }

    private static PublicKey parsePublicKey(String pem) throws Exception {
        String normalized = pem
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s+", "");
        byte[] decoded = Base64.getDecoder().decode(normalized);
        X509EncodedKeySpec spec = new X509EncodedKeySpec(decoded);
        try {
            return KeyFactory.getInstance("RSA").generatePublic(spec);
        } catch (Exception ignored) {
            return KeyFactory.getInstance("EC").generatePublic(spec);
        }
    }
}
