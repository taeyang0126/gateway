package com.lei.gateway.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.lei.gateway.config.GatewayProperties;
import com.lei.gateway.config.PluginConfigEntry;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PostAuthRateLimitIntegrationTest extends IntegrationTestBase {

    private static final KeyPair KEY_PAIR = createKeyPair();

    @Override
    protected GatewayProperties createGatewayProperties() {
        GatewayProperties props = super.createGatewayProperties();
        PluginConfigEntry realIp = new PluginConfigEntry();
        realIp.setName("real-ip");

        PluginConfigEntry auth = new PluginConfigEntry();
        auth.setName("auth");
        auth.setConfig(Map.of(
                "type", "JWT",
                "providers", Map.of("jwt", Map.of(
                        "issuer", "integration-issuer",
                        "audience", "integration-audience",
                        "public-key", toPem(
                                (RSAPublicKey) KEY_PAIR.getPublic())))));

        PluginConfigEntry ipRateLimit = new PluginConfigEntry();
        ipRateLimit.setName("ip-rate-limit");
        ipRateLimit.setConfig(Map.of(
                "permits-per-second", 100,
                "burst-capacity", 100));

        PluginConfigEntry userRateLimit = new PluginConfigEntry();
        userRateLimit.setName("user-rate-limit");
        userRateLimit.setConfig(Map.of(
                "permits-per-second", 1,
                "burst-capacity", 1));

        props.setPlugins(List.of(realIp, auth, ipRateLimit, userRateLimit));
        return props;
    }

    @Test
    void sameUserSecondRequestShouldHitUserRateLimit()
            throws Exception {
        String token = createToken("user-a");
        HttpRequest request = HttpRequest.newBuilder()
                .uri(gatewayUri("/api/example/hello"))
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();

        HttpResponse<String> first = httpClient.send(
                request, HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> second = httpClient.send(
                request, HttpResponse.BodyHandlers.ofString());

        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(second.statusCode()).isEqualTo(429);
        assertThat(second.headers().firstValue("Retry-After"))
                .isPresent();
        assertThat(second.body()).contains("user-rate-limit");
        assertThat(meterRegistry.find("gateway.plugin.decisions")
                .tag("plugin", "user-rate-limit")
                .tag("decision", "SHORT_CIRCUIT")
                .counter())
                .isNotNull();
    }

    private static KeyPair createKeyPair() {
        try {
            KeyPairGenerator gen =
                    KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            return gen.generateKeyPair();
        } catch (Exception ex) {
            throw new IllegalStateException(
                    "Failed to create key pair", ex);
        }
    }

    private static String createToken(String subject)
            throws Exception {
        RSAPublicKey pub = (RSAPublicKey) KEY_PAIR.getPublic();
        RSAPrivateKey priv = (RSAPrivateKey) KEY_PAIR.getPrivate();
        RSAKey rsaKey = new RSAKey.Builder(pub)
                .privateKey(priv).keyID("k1").build();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(subject)
                .issuer("integration-issuer")
                .audience("integration-audience")
                .expirationTime(Date.from(
                        Instant.now().plusSeconds(300)))
                .build();
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256)
                        .type(JOSEObjectType.JWT)
                        .keyID("k1").build(),
                claims);
        JWSSigner signer = new RSASSASigner(rsaKey);
        jwt.sign(signer);
        return jwt.serialize();
    }

    private static String toPem(RSAPublicKey publicKey) {
        String encoded = Base64.getMimeEncoder(
                64, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(publicKey.getEncoded());
        return "-----BEGIN PUBLIC KEY-----\n"
                + encoded
                + "\n-----END PUBLIC KEY-----";
    }
}
