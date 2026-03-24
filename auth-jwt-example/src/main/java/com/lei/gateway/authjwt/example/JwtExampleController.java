package com.lei.gateway.authjwt.example;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * JWT 示例接口。
 */
@RestController
@RequestMapping("/api/auth-jwt")
public class JwtExampleController {

    private final ExampleJwtIssuer jwtIssuer;
    private final ExampleJwtVerifier jwtVerifier;
    private final JwtExampleProperties properties;

    /**
     * 创建控制器。
     */
    public JwtExampleController(ExampleJwtIssuer jwtIssuer,
            ExampleJwtVerifier jwtVerifier,
            JwtExampleProperties properties) {
        this.jwtIssuer = jwtIssuer;
        this.jwtVerifier = jwtVerifier;
        this.properties = properties;
    }

    /**
     * 生成 JWT。
     */
    @PostMapping("/token")
    public Map<String, Object> issueToken(
            @RequestBody(required = false) TokenIssueRequest request)
            throws Exception {
        String userId = request == null || request.userId() == null
                || request.userId().isBlank()
                        ? "demo-user" : request.userId().trim();
        String token = jwtIssuer.issueToken(userId);
        return Map.of(
                "tokenType", "Bearer",
                "accessToken", token,
                "userId", userId,
                "expiresIn", properties.getTokenExpiresSeconds(),
                "issuer", properties.getIssuer(),
                "audience", properties.getAudience());
    }

    /**
     * 校验 JWT。
     */
    @PostMapping("/verify")
    public Map<String, Object> verifyToken(
            @RequestBody TokenVerifyRequest request) {
        if (request == null || request.token() == null
                || request.token().isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "token is required");
        }
        try {
            String subject = jwtVerifier.verify(request.token().trim());
            if (subject == null) {
                return Map.of("valid", false, "reason", "invalid_token");
            }
            return Map.of("valid", true, "subject", subject);
        } catch (Exception ex) {
            return Map.of("valid", false, "reason", "jwt_verify_error");
        }
    }

    /**
     * 签发请求。
     */
    public record TokenIssueRequest(String userId) {
    }

    /**
     * 校验请求。
     */
    public record TokenVerifyRequest(String token) {
    }
}
