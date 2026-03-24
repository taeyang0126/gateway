package com.lei.gateway.authjwt.example;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import org.springframework.stereotype.Component;

/**
 * 示例 JWT 签发器。
 */
@Component
public class ExampleJwtIssuer {

    private final JwtExampleProperties properties;

    /**
     * 创建签发器。
     */
    public ExampleJwtIssuer(JwtExampleProperties properties) {
        this.properties = properties;
    }

    /**
     * 签发 JWT。
     */
    public String issueToken(String userId) throws Exception {
        RSAPrivateKey privateKey = parsePrivateKey(properties.getPrivateKey());
        JWSSigner signer = new RSASSASigner(privateKey);
        Instant now = Instant.now();
        JWTClaimsSet claimsSet = new JWTClaimsSet.Builder()
                .subject(userId)
                .issuer(properties.getIssuer())
                .audience(properties.getAudience())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(
                        now.plusSeconds(properties.getTokenExpiresSeconds())))
                .build();
        SignedJWT signedJwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256)
                        .type(JOSEObjectType.JWT)
                        .keyID(properties.getKeyId())
                        .build(),
                claimsSet);
        signedJwt.sign(signer);
        return signedJwt.serialize();
    }

    private static RSAPrivateKey parsePrivateKey(String pem) throws Exception {
        if (pem == null || pem.isBlank()) {
            throw new IllegalStateException("auth.jwt.private-key is required");
        }
        String normalized = pem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s+", "");
        byte[] decoded = Base64.getDecoder().decode(normalized);
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(decoded);
        return (RSAPrivateKey) KeyFactory.getInstance("RSA").generatePrivate(spec);
    }
}
