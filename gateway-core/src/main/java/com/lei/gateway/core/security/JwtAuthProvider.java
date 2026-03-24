package com.lei.gateway.core.security;

import com.lei.gateway.core.config.SecurityProperties;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.text.ParseException;
import java.time.Instant;
import java.util.Date;

/**
 * 本地 JWT 认证提供器。
 */
public class JwtAuthProvider implements AuthProvider {

    private final JwksKeyProvider jwksKeyProvider;

    /**
     * 创建 JWT 认证提供器。
     */
    public JwtAuthProvider(JwksKeyProvider jwksKeyProvider) {
        this.jwksKeyProvider = jwksKeyProvider;
    }

    @Override
    public SecurityProperties.AuthType type() {
        return SecurityProperties.AuthType.JWT;
    }

    @Override
    public AuthenticationResult authenticate(SecurityRequestContext context,
            EffectiveSecurityConfig.Auth authConfig) {
        EffectiveSecurityConfig.TokenExtractor tokenExtractor =
                authConfig.getTokenExtractor();
        String headerName = tokenExtractor.getTokenHeaderName();
        String authorization = context.getRequest().headers().get(headerName);
        if (authorization == null || authorization.isBlank()) {
            return AuthenticationResult.failed("missing_authorization_header");
        }
        String prefix = tokenExtractor.getTokenValuePrefix();
        if (prefix == null || prefix.isBlank()) {
            prefix = "Bearer ";
        }
        if (!authorization.startsWith(prefix)) {
            return AuthenticationResult.failed("invalid_authorization_scheme");
        }
        String token = authorization.substring(prefix.length()).trim();
        if (token.isBlank()) {
            return AuthenticationResult.failed("empty_bearer_token");
        }

        SignedJWT signedJwt;
        try {
            signedJwt = SignedJWT.parse(token);
        } catch (ParseException ex) {
            return AuthenticationResult.failed("invalid_jwt_format");
        }

        JWSVerifier verifier;
        try {
            verifier = jwksKeyProvider.resolveVerifier(
                    signedJwt.getHeader(), authConfig.getProviders().getJwt());
        } catch (Exception ex) {
            return AuthenticationResult.failed("jwks_resolve_error");
        }
        if (verifier == null) {
            return AuthenticationResult.failed("no_verifier_key");
        }

        try {
            if (!signedJwt.verify(verifier)) {
                return AuthenticationResult.failed("invalid_signature");
            }
        } catch (Exception ex) {
            return AuthenticationResult.failed("verify_error");
        }

        JWTClaimsSet claimsSet;
        try {
            claimsSet = signedJwt.getJWTClaimsSet();
        } catch (ParseException ex) {
            return AuthenticationResult.failed("invalid_claims");
        }

        EffectiveSecurityConfig.Jwt jwtConfig = authConfig.getProviders().getJwt();
        String issuer = jwtConfig.getIssuer();
        if (issuer != null && !issuer.isBlank()
                && !issuer.equals(claimsSet.getIssuer())) {
            return AuthenticationResult.failed("issuer_mismatch");
        }

        String audience = jwtConfig.getAudience();
        if (audience != null && !audience.isBlank()
                && (claimsSet.getAudience() == null
                || !claimsSet.getAudience().contains(audience))) {
            return AuthenticationResult.failed("audience_mismatch");
        }

        Date exp = claimsSet.getExpirationTime();
        if (exp == null) {
            return AuthenticationResult.failed("missing_exp");
        }
        if (exp.toInstant().isBefore(Instant.now())) {
            return AuthenticationResult.failed("token_expired");
        }

        String subject = claimsSet.getSubject();
        if (subject == null || subject.isBlank()) {
            return AuthenticationResult.failed("missing_subject");
        }
        return AuthenticationResult.success(subject);
    }
}
