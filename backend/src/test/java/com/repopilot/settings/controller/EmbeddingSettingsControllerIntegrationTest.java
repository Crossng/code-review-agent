package com.repopilot.settings.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repopilot.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "repopilot.embedding.mode=openai",
        "repopilot.embedding.api-base-url=https://api.openai.test/v1/",
        "repopilot.embedding.api-key=super-secret-embedding-key",
        "repopilot.embedding.model=text-embedding-test",
        "repopilot.embedding.timeout-seconds=45",
        "repopilot.embedding.batch-size=12",
        "repopilot.embedding.max-input-chars=9000",
        "repopilot.embedding.keyword-weight=0.4",
        "repopilot.embedding.vector-weight=0.6",
        "repopilot.embedding.minimum-similarity=0.35"
})
class EmbeddingSettingsControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    private String email;

    @BeforeEach
    void setUp() {
        email = "embedding-settings-" + UUID.randomUUID() + "@example.test";
    }

    @AfterEach
    void tearDown() {
        userRepository.findByEmail(email).ifPresent(userRepository::delete);
    }

    @Test
    void embeddingSettingsRequireAuthentication() throws Exception {
        mockMvc.perform(get("/api/settings/embedding"))
                .andExpect(status().isForbidden());
    }

    @Test
    void embeddingSettingsReturnSanitizedHybridSearchConfiguration() throws Exception {
        String token = register();

        MvcResult result = mockMvc.perform(get("/api/settings/embedding")
                        .header(AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.mode").value("openai"))
                .andExpect(jsonPath("$.data.provider").value("OPENAI_COMPATIBLE"))
                .andExpect(jsonPath("$.data.enabled").value(true))
                .andExpect(jsonPath("$.data.ready").value(true))
                .andExpect(jsonPath("$.data.embeddingAvailable").value(true))
                .andExpect(jsonPath("$.data.model").value("text-embedding-test"))
                .andExpect(jsonPath("$.data.apiBaseUrl").value("https://api.openai.test/v1"))
                .andExpect(jsonPath("$.data.apiKeyConfigured").value(true))
                .andExpect(jsonPath("$.data.apiKeyRequired").value(true))
                .andExpect(jsonPath("$.data.timeoutSeconds").value(45))
                .andExpect(jsonPath("$.data.batchSize").value(12))
                .andExpect(jsonPath("$.data.maxInputChars").value(9000))
                .andExpect(jsonPath("$.data.keywordWeight").value(0.4))
                .andExpect(jsonPath("$.data.vectorWeight").value(0.6))
                .andExpect(jsonPath("$.data.minimumSimilarity").value(0.35))
                .andExpect(jsonPath("$.data.fallbackMode").value("KEYWORD"))
                .andExpect(jsonPath("$.data.missingRequirements").isEmpty())
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).doesNotContain("super-secret-embedding-key");
    }

    private String register() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", email,
                                "password", "password123",
                                "displayName", "Embedding Settings"
                        ))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data").path("token").asText();
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }
}
