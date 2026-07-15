package com.repopilot.modelcall.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import com.repopilot.agent.domain.AgentRun;
import com.repopilot.agent.domain.AgentTask;
import com.repopilot.agent.domain.AgentTaskType;
import com.repopilot.agent.repository.AgentRunRepository;
import com.repopilot.agent.repository.AgentTaskRepository;
import com.repopilot.modelcall.repository.ModelCallLogRepository;
import com.repopilot.modelcall.service.ModelCallLogService;
import com.repopilot.project.domain.Project;
import com.repopilot.project.repository.ProjectRepository;
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
class ModelCallLogControllerIntegrationTest {

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
    private ModelCallLogRepository modelCallLogRepository;

    @Autowired
    private ModelCallLogService modelCallLogService;

    private String ownerEmail;
    private String otherEmail;
    private Long runId;
    private AgentTask task;
    private Project project;

    @BeforeEach
    void setUp() {
        ownerEmail = "model-call-owner-" + UUID.randomUUID() + "@example.test";
        otherEmail = "model-call-other-" + UUID.randomUUID() + "@example.test";
    }

    @AfterEach
    void tearDown() {
        if (runId != null) {
            modelCallLogRepository.deleteAll(modelCallLogRepository.findByAgentRunIdOrderByStartedAtAsc(runId));
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
    void listModelCallsReturnsAuditedCallsForRunOwnerOnly() throws Exception {
        String ownerToken = register(ownerEmail);
        String otherToken = register(otherEmail);
        User owner = userRepository.findByEmail(ownerEmail).orElseThrow();
        AgentRun run = createRun(owner);

        modelCallLogService.record(
                run,
                "plan_task",
                Map.of("prompt", "Plan work", "modelApiKey", "secret-value"),
                () -> Map.of("summary", "Plan created")
        );
        assertThatThrownBy(() -> modelCallLogService.record(
                run,
                "generate_patch",
                Map.of("prompt", "Generate patch"),
                () -> {
                    throw new IllegalStateException("Model failed");
                }
        )).isInstanceOf(IllegalStateException.class);

        MvcResult result = mockMvc.perform(get("/api/agent/runs/{runId}/model-calls", runId)
                        .header(AUTHORIZATION, bearer(ownerToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn();

        JsonNode calls = data(result);
        assertThat(calls).hasSize(2);
        assertThat(calls).anySatisfy(call -> {
            assertThat(call.path("stepName").asText()).isEqualTo("plan_task");
            assertThat(call.path("modelProvider").asText()).isEqualTo("LOCAL_PLACEHOLDER");
            assertThat(call.path("modelName").asText()).isEqualTo("deterministic-mvp");
            assertThat(call.path("status").asText()).isEqualTo("SUCCESS");
            assertThat(call.path("promptJson").asText()).contains("[REDACTED]").doesNotContain("secret-value");
            assertThat(call.path("responseJson").asText()).contains("Plan created");
            assertThat(call.path("totalTokens").asInt()).isGreaterThan(0);
            assertThat(call.path("durationMs").asInt()).isGreaterThanOrEqualTo(0);
        });
        assertThat(calls).anySatisfy(call -> {
            assertThat(call.path("stepName").asText()).isEqualTo("generate_patch");
            assertThat(call.path("status").asText()).isEqualTo("FAILED");
            assertThat(call.path("errorMessage").asText()).isEqualTo("Model failed");
        });

        mockMvc.perform(get("/api/agent/runs/{runId}/model-calls", runId)
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
                "Trace model calls",
                "Verify model call auditing"
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
                                "displayName", "Model Trace User"
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
