package com.lei.gateway.authjwt.example;

import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.crypto.RSASSAVerifier;
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
import org.springframework.stereotype.Component;

/**
 * 示例 JWT 验签密钥提供器。
 */
@Component
public class ExampleJwtKeyProvider {

    private final JwtExampleProperties properties;
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final Map<String, JWK> kidToKey = new ConcurrentHashMap<>();
    private final AtomicLong lastRefreshMillis = new AtomicLong(0L);
    private volatile JWK defaultKey;

    /**
     * 创建密钥提供器。
     */
    public ExampleJwtKeyProvider(JwtExampleProperties properties) {
        this.properties = properties;
    }

    /**
     * 解析验签器。
     */
    public JWSVerifier resolveVerifier(JWSHeader header) throws Exception {
        JWK key = resolveKey(header.getKeyID());
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

    private JWK resolveKey(String kid) throws Exception {
        String publicKey = properties.getPublicKey();
        if (publicKey != null && !publicKey.isBlank()) {
            return fromPublicKey(publicKey);
        }
        String jwksUrl = properties.getJwksUrl();
        if (jwksUrl == null || jwksUrl.isBlank()) {
            return null;
        }

        refreshIfNeeded(false);
        JWK key = pickKey(kid);
        if (key != null) {
            return key;
        }
        refreshIfNeeded(true);
        return pickKey(kid);
    }

    private synchronized void refreshIfNeeded(boolean force) throws Exception {
        long now = System.currentTimeMillis();
        long refreshMillis = properties.getJwksRefreshSeconds() * 1000L;
        if (!force
                && now - lastRefreshMillis.get() < refreshMillis
                && !kidToKey.isEmpty()) {
            return;
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(properties.getJwksUrl()))
                .timeout(Duration.ofMillis(properties.getJwksTimeoutMillis()))
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("Fetch jwks failed");
        }
        JWKSet jwkSet = JWKSet.parse(response.body());
        List<JWK> keys = jwkSet.getKeys();
        kidToKey.clear();
        for (JWK key : keys) {
            if (key.getKeyID() != null) {
                kidToKey.put(key.getKeyID(), key);
            }
        }
        defaultKey = keys.isEmpty() ? null : keys.get(0);
        lastRefreshMillis.set(now);
    }

    private JWK pickKey(String kid) {
        if (kid != null && !kid.isBlank()) {
            JWK byKid = kidToKey.get(kid);
            if (byKid != null) {
                return byKid;
            }
        }
        return defaultKey;
    }

    private static JWK fromPublicKey(String pem) throws Exception {
        String normalized = pem
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s+", "");
        byte[] decoded = Base64.getDecoder().decode(normalized);
        X509EncodedKeySpec spec = new X509EncodedKeySpec(decoded);
        try {
            PublicKey publicKey = KeyFactory.getInstance("RSA").generatePublic(spec);
            return new RSAKey.Builder((RSAPublicKey) publicKey).build();
        } catch (Exception ignored) {
            PublicKey publicKey = KeyFactory.getInstance("EC").generatePublic(spec);
            ECPublicKey ecPublicKey = (ECPublicKey) publicKey;
            return new ECKey.Builder(
                    com.nimbusds.jose.jwk.Curve.forECParameterSpec(
                            ecPublicKey.getParams()),
                    ecPublicKey).build();
        }
    }
}
