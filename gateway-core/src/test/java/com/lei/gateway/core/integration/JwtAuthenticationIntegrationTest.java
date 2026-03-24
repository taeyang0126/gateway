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

class JwtAuthenticationIntegrationTest extends IntegrationTestBase {

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
        return security;
    }

    @Test
    void missingJwtShouldReturn401() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(gatewayUri("/api/example/hello"))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(
                request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).contains("missing_authorization_header");
        assertThat(upstreamServer.getReceivedRequests()).isEmpty();
    }

    @Test
    void malformedJwtShouldReturn401() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(gatewayUri("/api/example/hello"))
                .header("Authorization", "Bearer not-a-jwt")
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(
                request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).contains("invalid_jwt_format");
        assertThat(upstreamServer.getReceivedRequests()).isEmpty();
    }

    @Test
    void validJwtShouldPassAndForward() throws Exception {
        String token = createToken("integration-user");

        HttpRequest request = HttpRequest.newBuilder()
                .uri(gatewayUri("/api/example/hello"))
                .header("Authorization", "Bearer " + token)
                .header("x-userId", "forged-user")
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(
                request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("Hello from upstream!");
        assertThat(upstreamServer.getReceivedRequests()).hasSize(1);
        assertThat(upstreamServer.getReceivedRequests().get(0)
                .headers().get("x-userId")).isEqualTo("integration-user");
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
