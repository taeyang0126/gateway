package com.lei.gateway.core.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/**
 * AccessLogEntry JSON 序列化测试。
 */
class AccessLogEntryTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void serializesAllFieldsToJson() throws Exception {
        AccessLogEntry entry = new AccessLogEntry();
        entry.setMethod("POST");
        entry.setPath("/api/example/upload");
        entry.setStatusCode(200);
        entry.setDurationMs(42);
        entry.setClientIp("192.168.1.100");
        entry.setUpstream("http://localhost:8081");
        entry.setRequestBodySize(1048576);
        entry.setResponseBodySize(256);
        entry.setTraceId("4bf92f3577b34da6a3ce929d0e0e4736");
        entry.setAuthRequired(true);
        entry.setAuthPassed(true);
        entry.setSecurityDecision("ALLOW");
        entry.setSecurityFilter("auth");
        entry.setSecurityReason("authenticated");

        String json = objectMapper.writeValueAsString(entry);
        JsonNode node = objectMapper.readTree(json);

        assertThat(node.get("method").asText()).isEqualTo("POST");
        assertThat(node.get("path").asText()).isEqualTo("/api/example/upload");
        assertThat(node.get("statusCode").asInt()).isEqualTo(200);
        assertThat(node.get("durationMs").asLong()).isEqualTo(42);
        assertThat(node.get("clientIp").asText()).isEqualTo("192.168.1.100");
        assertThat(node.get("upstream").asText()).isEqualTo("http://localhost:8081");
        assertThat(node.get("requestBodySize").asLong()).isEqualTo(1048576);
        assertThat(node.get("responseBodySize").asLong()).isEqualTo(256);
        assertThat(node.get("traceId").asText())
                .isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
        assertThat(node.get("authRequired").asBoolean()).isTrue();
        assertThat(node.get("authPassed").asBoolean()).isTrue();
        assertThat(node.get("securityDecision").asText()).isEqualTo("ALLOW");
        assertThat(node.get("securityFilter").asText()).isEqualTo("auth");
        assertThat(node.get("securityReason").asText()).isEqualTo("authenticated");
    }

    @Test
    void nullTraceIdIsExcludedFromJson() throws Exception {
        AccessLogEntry entry = new AccessLogEntry();
        entry.setMethod("GET");
        entry.setPath("/api/example/hello");
        entry.setStatusCode(200);
        entry.setDurationMs(5);
        entry.setClientIp("10.0.0.1");
        entry.setUpstream("http://localhost:8081");
        entry.setRequestBodySize(0);
        entry.setResponseBodySize(128);
        // traceId 不设置，默认 null

        String json = objectMapper.writeValueAsString(entry);
        JsonNode node = objectMapper.readTree(json);

        assertThat(node.has("traceId")).isFalse();
        assertThat(node.get("method").asText()).isEqualTo("GET");
    }
}
