package com.repopilot.settings.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
        "repopilot.workspace-root=target/sandbox-settings-workspace",
        "repopilot.sandbox.maven-cache=target/sandbox-settings-m2",
        "repopilot.sandbox.docker-image=maven:3.9-eclipse-temurin-17",
        "repopilot.sandbox.timeout-seconds=321",
        "repopilot.sandbox.docker-check-enabled=false"
})
class SandboxSettingsControllerIntegrationTest {

    private static final Path WORKSPACE_PATH = Path.of("target/sandbox-settings-workspace")
            .toAbsolutePath()
            .normalize();
    private static final Path MAVEN_CACHE_PATH = Path.of("target/sandbox-settings-m2")
            .toAbsolutePath()
            .normalize();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    private String email;

    @BeforeEach
    void setUp() throws IOException {
        email = "sandbox-settings-" + UUID.randomUUID() + "@example.test";
        Files.createDirectories(WORKSPACE_PATH);
        Files.createDirectories(MAVEN_CACHE_PATH);
    }

    @AfterEach
    void tearDown() {
        userRepository.findByEmail(email).ifPresent(userRepository::delete);
    }

    @Test
    void sandboxSettingsRequireAuthentication() throws Exception {
        mockMvc.perform(get("/api/settings/sandbox"))
                .andExpect(status().isForbidden());
    }

    @Test
    void sandboxSettingsReturnRuntimeConfigurationAndChecks() throws Exception {
        String token = register();

        MvcResult result = mockMvc.perform(get("/api/settings/sandbox")
                        .header(AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.ready").value(true))
                .andExpect(jsonPath("$.data.dockerImage").value("maven:3.9-eclipse-temurin-17"))
                .andExpect(jsonPath("$.data.dockerImageConfigured").value(true))
                .andExpect(jsonPath("$.data.timeoutSeconds").value(321))
                .andExpect(jsonPath("$.data.workspaceRoot").value(WORKSPACE_PATH.toString()))
                .andExpect(jsonPath("$.data.workspaceRootExists").value(true))
                .andExpect(jsonPath("$.data.workspaceRootWritable").value(true))
                .andExpect(jsonPath("$.data.mavenCachePath").value(MAVEN_CACHE_PATH.toString()))
                .andExpect(jsonPath("$.data.mavenCacheExists").value(true))
                .andExpect(jsonPath("$.data.mavenCacheWritable").value(true))
                .andExpect(jsonPath("$.data.dockerCheckEnabled").value(false))
                .andExpect(jsonPath("$.data.dockerAvailable").value(false))
                .andExpect(jsonPath("$.data.dockerVersion").doesNotExist())
                .andExpect(jsonPath("$.data.missingRequirements").isEmpty())
                .andReturn();

        JsonNode checks = objectMapper.readTree(result.getResponse().getContentAsString()).path("data").path("checks");
        assertThat(checks).extracting(node -> node.path("code").asText())
                .contains("DOCKER_DAEMON", "DOCKER_IMAGE", "WORKSPACE_ROOT", "MAVEN_CACHE", "TIMEOUT");
        assertThat(checks).extracting(node -> node.path("status").asText())
                .contains("WARN", "PASS");
    }

    private String register() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(APPLICATION_JSON)
                        .content(json(Map.of(
                                "email", email,
                                "password", "password123",
                                "displayName", "Sandbox Settings"
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
