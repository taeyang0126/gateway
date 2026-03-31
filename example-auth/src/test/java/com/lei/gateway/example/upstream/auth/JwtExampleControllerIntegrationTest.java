package com.lei.gateway.example.upstream.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class JwtExampleControllerIntegrationTest {

    @Test
    void issueTokenShouldReturnBearerToken() throws Exception {
        JwtExampleProperties properties = createProperties();
        JwtExampleController controller = new JwtExampleController(
                new ExampleJwtIssuer(properties) {
                    @Override
                    public String issueToken(String userId) {
                        return "token-for-" + userId;
                    }
                },
                new ExampleJwtVerifier(properties, new ExampleJwtKeyProvider(properties)) {
                    @Override
                    public String verify(String token) {
                        return "ignored";
                    }
                },
                properties);

        Map<String, Object> result = controller.issueToken(
                new JwtExampleController.TokenIssueRequest("test-user"));

        assertThat(result.get("tokenType")).isEqualTo("Bearer");
        assertThat(result.get("userId")).isEqualTo("test-user");
        assertThat(result.get("accessToken")).isEqualTo("token-for-test-user");
        assertThat(result.get("issuer")).isEqualTo("test-issuer");
        assertThat(result.get("audience")).isEqualTo("test-audience");
        assertThat(result.get("expiresIn")).isEqualTo(1200);
    }

    @Test
    void verifyInvalidTokenShouldReturnFailedResult() {
        JwtExampleProperties properties = createProperties();
        JwtExampleController controller = new JwtExampleController(
                new ExampleJwtIssuer(properties) {
                    @Override
                    public String issueToken(String userId) {
                        return "ignored";
                    }
                },
                new ExampleJwtVerifier(properties, new ExampleJwtKeyProvider(properties)) {
                    @Override
                    public String verify(String token) {
                        return null;
                    }
                },
                properties);

        Map<String, Object> result = controller.verifyToken(
                new JwtExampleController.TokenVerifyRequest("not-a-jwt"));

        assertThat(result.get("valid")).isEqualTo(Boolean.FALSE);
        assertThat(result.get("reason")).isEqualTo("invalid_token");
    }

    private static JwtExampleProperties createProperties() {
        JwtExampleProperties properties = new JwtExampleProperties();
        properties.setIssuer("test-issuer");
        properties.setAudience("test-audience");
        properties.setTokenExpiresSeconds(1200);
        return properties;
    }
}
