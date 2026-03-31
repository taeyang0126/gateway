package com.lei.gateway.example.upstream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * DelayController 单元测试。
 */
@WebMvcTest(DelayController.class)
class DelayControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void fixedDelay_returnsCorrectFields() throws Exception {
        mockMvc.perform(get("/api/example/delay/fixed").param("ms", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.delay").value(10))
                .andExpect(jsonPath("$.type").value("fixed"));
    }

    @Test
    void fixedDelay_defaultMs_returns50() throws Exception {
        mockMvc.perform(get("/api/example/delay/fixed"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.delay").value(50))
                .andExpect(jsonPath("$.type").value("fixed"));
    }

    @Test
    void randomDelay_delayWithinRange() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/example/delay/random")
                        .param("min", "10")
                        .param("max", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("random"))
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        int delay = body.get("delay").asInt();
        assertThat(delay).isBetween(10, 20);
    }

    @Test
    void fixedDelay_negativeMs_returns400() throws Exception {
        mockMvc.perform(get("/api/example/delay/fixed").param("ms", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("ms must be non-negative"));
    }

    @Test
    void randomDelay_minGreaterThanMax_returns400() throws Exception {
        mockMvc.perform(get("/api/example/delay/random")
                        .param("min", "10")
                        .param("max", "5"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("min must not exceed max"));
    }

    @Test
    void randomDelay_negativeMin_returns400() throws Exception {
        mockMvc.perform(get("/api/example/delay/random")
                        .param("min", "-1")
                        .param("max", "10"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("min and max must be non-negative"));
    }

    @Test
    void slowDelay_returns500msAndCorrectFields() throws Exception {
        mockMvc.perform(get("/api/example/delay/slow"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.delay").value(500))
                .andExpect(jsonPath("$.type").value("slow"));
    }
}
