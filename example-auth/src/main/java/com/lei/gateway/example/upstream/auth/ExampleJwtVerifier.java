package com.lei.gateway.example.upstream.auth;

import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Instant;
import java.util.Date;
import org.springframework.stereotype.Component;

/**
 * 示例 JWT 验证器。
 */
@Component
public class ExampleJwtVerifier {

    private final JwtExampleProperties properties;
    private final ExampleJwtKeyProvider keyProvider;

    /**
     * 创建 JWT 验证器。
     */
    public ExampleJwtVerifier(JwtExampleProperties properties,
            ExampleJwtKeyProvider keyProvider) {
        this.properties = properties;
        this.keyProvider = keyProvider;
    }

    /**
     * 验证并返回主体标识。
     */
    public String verify(String token) throws Exception {
        SignedJWT jwt = SignedJWT.parse(token);
        JWSVerifier verifier = keyProvider.resolveVerifier(jwt.getHeader());
        if (verifier == null || !jwt.verify(verifier)) {
            return null;
        }

        JWTClaimsSet claimsSet = jwt.getJWTClaimsSet();
        if (!properties.getIssuer().equals(claimsSet.getIssuer())) {
            return null;
        }
        if (claimsSet.getAudience() == null
                || !claimsSet.getAudience().contains(properties.getAudience())) {
            return null;
        }
        Date exp = claimsSet.getExpirationTime();
        if (exp == null || exp.toInstant().isBefore(Instant.now())) {
            return null;
        }
        return claimsSet.getSubject();
    }
}
