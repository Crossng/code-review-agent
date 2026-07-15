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

import com.fasterxml.jackson.databind.JsonNode;
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
        "repopilot.coder.mode=openai-compatible",
        "repopilot.coder.api-base-url=https://api.openai.test/v1/",
        "repopilot.coder.api-key=super-secret-coder-key",
        "repopilot.coder.model=gpt-test-coder",
        "repopilot.coder.timeout-seconds=45",
        "repopilot.coder.max-completion-tokens=2048",
        "repopilot.coder.instruction-role=system",
        "repopilot.coder.organization=org-secret",
        "repopilot.coder.project=project-secret"
})
class CoderSettingsControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    private String email;

    @BeforeEach
    void setUp() {
        email = "coder-settings-" + UUID.randomUUID() + "@example.test";
    }

    @AfterEach
    void tearDown() {
        userRepository.findByEmail(email).ifPresent(userRepository::delete);
    }

    @Test
    void coderSettingsRequireAuthentication() throws Exception {
        mockMvc.perform(get("/api/settings/coder"))
                .andExpect(status().isForbidden());
    }

    @Test
    void coderSettingsReturnSanitizedRuntimeConfiguration() throws Exception {
        String token = register();

        MvcResult result = mockMvc.perform(get("/api/settings/coder")
                        .header(AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.mode").value("openai-compatible"))
                .andExpect(jsonPath("$.data.provider").value("OPENAI_COMPATIBLE"))
                .andExpect(jsonPath("$.data.enabled").value(true))
                .andExpect(jsonPath("$.data.ready").value(true))
                .andExpect(jsonPath("$.data.model").value("gpt-test-coder"))
                .andExpect(jsonPath("$.data.apiBaseUrl").value("https://api.openai.test/v1"))
                .andExpect(jsonPath("$.data.apiKeyConfigured").value(true))
                .andExpect(jsonPath("$.data.fixtureConfigured").value(false))
                .andExpect(jsonPath("$.data.timeoutSeconds").value(45))
                .andExpect(jsonPath("$.data.maxCompletionTokens").value(2048))
                .andExpect(jsonPath("$.data.instructionRole").value("system"))
                .andExpect(jsonPath("$.data.organizationConfigured").value(true))
                .andExpect(jsonPath("$.data.projectConfigured").value(true))
                .andExpect(jsonPath("$.data.missingRequirements").isEmpty())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body)
                .doesNotContain("super-secret-coder-key")
                .doesNotContain("org-secret")
                .doesNotContain("project-secret");
        JsonNode supportedModes = objectMapper.readTree(body).path("data").path("supportedModes");
        assertThat(supportedModes).extracting(JsonNode::asText)
                .contains("disabled", "fixture", "openai", "openai-compatible");
    }

    private String register() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(APPLICATION_JSON)
                        .content(json(Map.of(
                                "email", email,
                                "password", "password123",
                                "displayName", "Coder Settings"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data").path("token").asText();
    }

    private String json(Object value) throws IOException {
        return objectMapper.writeValueAsString(value);
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }
}
