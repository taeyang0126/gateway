package com.lei.gateway.core.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.lei.gateway.core.config.SecurityProperties;
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
import org.junit.jupiter.api.Test;

class PostAuthRateLimitIntegrationTest extends IntegrationTestBase {

    private static final KeyPair KEY_PAIR = createKeyPair();

    @Override
    protected SecurityProperties createSecurityProperties() {
        SecurityProperties security = new SecurityProperties();
        security.setEnabled(true);

        security.getAuth().setEnabled(true);
        security.getAuth().setType(SecurityProperties.AuthType.JWT);
        security.getAuth().getProviders().getJwt().setIssuer("integration-issuer");
        security.getAuth().getProviders().getJwt().setAudience("integration-audience");
        security.getAuth().getProviders().getJwt().setPublicKey(
                toPem((RSAPublicKey) KEY_PAIR.getPublic()));

        security.getRateLimit().getIp().setEnabled(true);
        security.getRateLimit().getIp().setMode(SecurityProperties.RateLimitMode.LOCAL);
        security.getRateLimit().getIp().setPermitsPerSecond(100);
        security.getRateLimit().getIp().setBurstCapacity(100);
        security.getRateLimit().getUser().setEnabled(true);
        security.getRateLimit().getUser().setMode(SecurityProperties.RateLimitMode.LOCAL);
        security.getRateLimit().getUser().setPermitsPerSecond(1);
        security.getRateLimit().getUser().setBurstCapacity(1);
        return security;
    }

    @Test
    void sameUserSecondRequestShouldHitUserRateLimit() throws Exception {
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
        assertThat(second.headers().firstValue("Retry-After")).isPresent();
        assertThat(second.body()).contains("user-rate-limit");
        assertThat(meterRegistry.find("gateway.security.rate_limit.hits")
                .tag("stage", "user-rate-limit")
                .tag("routeId", "example-service")
                .counter())
                .isNotNull();
    }

    private static KeyPair createKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to create key pair", ex);
        }
    }

    private static String createToken(String subject) throws Exception {
        RSAPublicKey publicKey = (RSAPublicKey) KEY_PAIR.getPublic();
        RSAPrivateKey privateKey = (RSAPrivateKey) KEY_PAIR.getPrivate();
        RSAKey rsaKey = new RSAKey.Builder(publicKey)
                .privateKey(privateKey)
                .keyID("k1")
                .build();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(subject)
                .issuer("integration-issuer")
                .audience("integration-audience")
                .expirationTime(Date.from(Instant.now().plusSeconds(300)))
                .build();
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256)
                        .type(JOSEObjectType.JWT)
                        .keyID("k1")
                        .build(),
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
