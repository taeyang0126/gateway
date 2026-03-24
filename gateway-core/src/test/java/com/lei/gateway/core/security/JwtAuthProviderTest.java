package com.lei.gateway.core.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import org.junit.jupiter.api.Test;

class JwtAuthProviderTest {

    @Test
    void shouldAuthenticateWithStaticPublicKey() throws Exception {
        KeyPair keyPair = generateRsaKeyPair();
        String token = createToken(keyPair, "demo-user",
                "test-issuer", "test-audience");

        JwtAuthProvider provider = new JwtAuthProvider(new JwksKeyProvider());
        EffectiveSecurityConfig.Auth authConfig = createAuthConfig(
                toPem((RSAPublicKey) keyPair.getPublic()),
                "test-issuer", "test-audience");
        SecurityRequestContext context = createContext(token);

        AuthenticationResult result = provider.authenticate(context, authConfig);
        assertThat(result.isAuthenticated()).isTrue();
        assertThat(result.getUserId()).isEqualTo("demo-user");
    }

    @Test
    void shouldFailWhenAudienceMismatch() throws Exception {
        KeyPair keyPair = generateRsaKeyPair();
        String token = createToken(keyPair, "demo-user",
                "test-issuer", "a1");

        JwtAuthProvider provider = new JwtAuthProvider(new JwksKeyProvider());
        EffectiveSecurityConfig.Auth authConfig = createAuthConfig(
                toPem((RSAPublicKey) keyPair.getPublic()),
                "test-issuer", "a2");
        SecurityRequestContext context = createContext(token);

        AuthenticationResult result = provider.authenticate(context, authConfig);
        assertThat(result.isAuthenticated()).isFalse();
        assertThat(result.getReason()).isEqualTo("audience_mismatch");
    }

    @Test
    void shouldReturnJwksResolveErrorWhenKeyProviderThrows() throws Exception {
        KeyPair keyPair = generateRsaKeyPair();
        String token = createToken(keyPair, "demo-user",
                "test-issuer", "test-audience");

        JwksKeyProvider keyProvider = mock(JwksKeyProvider.class);
        when(keyProvider.resolveVerifier(any(), any()))
                .thenThrow(new IllegalStateException("jwks unavailable"));
        JwtAuthProvider provider = new JwtAuthProvider(keyProvider);

        EffectiveSecurityConfig.Auth authConfig = createAuthConfig(
                null, "test-issuer", "test-audience");
        SecurityRequestContext context = createContext(token);

        AuthenticationResult result = provider.authenticate(context, authConfig);
        assertThat(result.isAuthenticated()).isFalse();
        assertThat(result.getReason()).isEqualTo("jwks_resolve_error");
    }

    private static SecurityRequestContext createContext(String token) {
        ChannelHandlerContext channelHandlerContext = mock(ChannelHandlerContext.class);
        Channel channel = mock(Channel.class);
        when(channelHandlerContext.channel()).thenReturn(channel);
        when(channel.remoteAddress()).thenReturn(new InetSocketAddress("127.0.0.1", 8080));

        DefaultFullHttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.GET, "/api/example/hello");
        request.headers().set("Authorization", "Bearer " + token);

        com.lei.gateway.core.config.Route route = new com.lei.gateway.core.config.Route();
        route.setId("route-1");
        route.setPathPrefix("/api/**");
        route.setUpstream("http://localhost:8081");

        EffectiveSecurityConfig config = new EffectiveSecurityConfig(
                true,
                java.util.List.of(),
                null,
                new EffectiveSecurityConfig.IpAccess(false, false, true,
                        java.util.List.of(), java.util.List.of()),
                createAuthConfig("unused", null, null),
                new EffectiveSecurityConfig.RateLimit(
                        new EffectiveSecurityConfig.IpRateLimit(false, false,
                                com.lei.gateway.core.config.SecurityProperties
                                        .RateLimitMode.LOCAL,
                                100, 100, 50),
                        new EffectiveSecurityConfig.UserRateLimit(false, false,
                                com.lei.gateway.core.config.SecurityProperties
                                        .RateLimitMode.LOCAL,
                                100, 100, 50)));
        return new SecurityRequestContext(channelHandlerContext, request, route, config, "t1");
    }

    private static EffectiveSecurityConfig.Auth createAuthConfig(
            String publicKeyPem, String issuer, String audience) {
        EffectiveSecurityConfig.Jwt jwt = new EffectiveSecurityConfig.Jwt(
                issuer, audience, publicKeyPem, null, 300, 500, 1000);
        return new EffectiveSecurityConfig.Auth(
                true, false, true,
                com.lei.gateway.core.config.SecurityProperties.AuthType.JWT,
                new EffectiveSecurityConfig.TokenExtractor(
                        "Authorization", "Bearer "),
                new EffectiveSecurityConfig.Providers(jwt));
    }

    private static String createToken(KeyPair keyPair, String subject,
            String issuer, String audience) throws JOSEException {
        RSAPublicKey publicKey = (RSAPublicKey) keyPair.getPublic();
        RSAPrivateKey privateKey = (RSAPrivateKey) keyPair.getPrivate();
        RSAKey rsaJwk = new RSAKey.Builder(publicKey)
                .privateKey(privateKey)
                .keyID("k1")
                .build();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(subject)
                .issuer(issuer)
                .audience(audience)
                .expirationTime(Date.from(Instant.now().plusSeconds(3600)))
                .build();
        SignedJWT signedJwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256)
                        .type(JOSEObjectType.JWT)
                        .keyID("k1")
                        .build(),
                claims);
        JWSSigner signer = new RSASSASigner(rsaJwk);
        signedJwt.sign(signer);
        return signedJwt.serialize();
    }

    private static KeyPair generateRsaKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keyPair = generator.generateKeyPair();
        // 验证可被 X509 解析，防止测试 key 结构异常。
        KeyFactory.getInstance("RSA").generatePublic(
                new X509EncodedKeySpec(keyPair.getPublic().getEncoded()));
        return keyPair;
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
