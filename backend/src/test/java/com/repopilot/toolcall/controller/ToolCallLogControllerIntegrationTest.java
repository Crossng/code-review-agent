package com.repopilot.toolcall.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.repopilot.agent.domain.AgentRun;
import com.repopilot.agent.domain.AgentTask;
import com.repopilot.agent.domain.AgentTaskType;
import com.repopilot.agent.repository.AgentRunRepository;
import com.repopilot.agent.repository.AgentTaskRepository;
import com.repopilot.project.domain.Project;
import com.repopilot.project.repository.ProjectRepository;
import com.repopilot.toolcall.repository.ToolCallLogRepository;
import com.repopilot.toolcall.service.ToolCallLogService;
import com.repopilot.user.domain.User;
import com.repopilot.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ToolCallLogControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private AgentTaskRepository agentTaskRepository;

    @Autowired
    private AgentRunRepository agentRunRepository;

    @Autowired
    private ToolCallLogRepository toolCallLogRepository;

    @Autowired
    private ToolCallLogService toolCallLogService;

    private String ownerEmail;
    private String otherEmail;
    private Long runId;
    private AgentTask task;
    private Project project;

    @BeforeEach
    void setUp() {
        ownerEmail = "tool-call-owner-" + UUID.randomUUID() + "@example.test";
        otherEmail = "tool-call-other-" + UUID.randomUUID() + "@example.test";
    }

    @AfterEach
    void tearDown() {
        if (runId != null) {
            toolCallLogRepository.deleteAll(toolCallLogRepository.findByAgentRunIdOrderByStartedAtAsc(runId));
            if (task != null) {
                task.setCurrentRun(null);
                agentTaskRepository.save(task);
            }
            agentRunRepository.findById(runId).ifPresent(agentRunRepository::delete);
        }
        if (task != null && task.getId() != null) {
            agentTaskRepository.findById(task.getId()).ifPresent(agentTaskRepository::delete);
        }
        if (project != null && project.getId() != null) {
            projectRepository.findById(project.getId()).ifPresent(projectRepository::delete);
        }
        userRepository.findByEmail(ownerEmail).ifPresent(userRepository::delete);
        userRepository.findByEmail(otherEmail).ifPresent(userRepository::delete);
    }

    @Test
    void listToolCallsReturnsAuditedCallsForRunOwnerOnly() throws Exception {
        String ownerToken = register(ownerEmail);
        String otherToken = register(otherEmail);
        User owner = userRepository.findByEmail(ownerEmail).orElseThrow();
        AgentRun run = createRun(owner);

        toolCallLogService.record(
                run,
                "search_code",
                Map.of("query", "UserService", "accessToken", "secret-value"),
                () -> Map.of("resultCount", 2)
        );
        toolCallLogService.record(
                run,
                "load_run_context",
                Map.of(),
                () -> Map.of(
                        "runId", run.getId(),
                        "retryAttemptCount", 1,
                        "retryAttempts", List.of(Map.of(
                                "attempt", 1,
                                "errorType", "BackendApiError",
                                "message", "Backend internal API failed with HTTP 503: temporary outage",
                                "retryable", true
                        ))
                )
        );
        assertThatThrownBy(() -> toolCallLogService.record(
                run,
                "run_maven_test",
                Map.of("command", "mvn -q test"),
                () -> {
                    throw new IllegalStateException("Maven failed");
                }
        )).isInstanceOf(IllegalStateException.class);

        MvcResult result = mockMvc.perform(get("/api/agent/runs/{runId}/tool-calls", runId)
                        .header(AUTHORIZATION, bearer(ownerToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn();

        JsonNode calls = data(result);
        assertThat(calls).hasSize(3);
        assertThat(calls).anySatisfy(call -> {
            assertThat(call.path("toolName").asText()).isEqualTo("search_code");
            assertThat(call.path("status").asText()).isEqualTo("SUCCESS");
            assertThat(call.path("inputJson").asText()).contains("[REDACTED]").doesNotContain("secret-value");
            assertThat(call.path("outputJson").asText()).contains("resultCount");
            assertThat(call.path("retryAudit").isNull()).isTrue();
            assertThat(call.path("durationMs").asInt()).isGreaterThanOrEqualTo(0);
        });
        assertThat(calls).anySatisfy(call -> {
            assertThat(call.path("toolName").asText()).isEqualTo("load_run_context");
            assertThat(call.path("retryAudit").path("attemptCount").asInt()).isEqualTo(1);
            assertThat(call.path("retryAudit").path("recovered").asBoolean()).isTrue();
            assertThat(call.path("retryAudit").path("firstFailureType").asText()).isEqualTo("BackendApiError");
            assertThat(call.path("retryAudit").path("firstFailureMessage").asText()).contains("HTTP 503");
        });
        assertThat(calls).anySatisfy(call -> {
            assertThat(call.path("toolName").asText()).isEqualTo("run_maven_test");
            assertThat(call.path("status").asText()).isEqualTo("FAILED");
            assertThat(call.path("errorMessage").asText()).isEqualTo("Maven failed");
        });

        mockMvc.perform(get("/api/agent/runs/{runId}/tool-calls", runId)
                        .header(AUTHORIZATION, bearer(otherToken)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AGENT_RUN_FORBIDDEN"));
    }

    private AgentRun createRun(User owner) {
        project = projectRepository.save(new Project(owner, "file:///tmp/demo.git", "example/demo", "main"));
        task = agentTaskRepository.save(new AgentTask(
                project,
                owner,
                AgentTaskType.FEATURE,
                "Trace tool calls",
                "Verify tool call auditing"
        ));
        AgentRun run = agentRunRepository.save(new AgentRun(task));
        task.setCurrentRun(run);
        agentTaskRepository.save(task);
        runId = run.getId();
        return run;
    }

    private String register(String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(APPLICATION_JSON)
                        .content(json(Map.of(
                                "email", email,
                                "password", "password123",
                                "displayName", "Trace User"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn();
        return data(result).path("token").asText();
    }

    private JsonNode data(MvcResult result) throws IOException {
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
    }

    private String json(Object value) throws IOException {
        return objectMapper.writeValueAsString(value);
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }
}
