package com.repopilot.agent.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.repopilot.agent.domain.AgentRun;
import com.repopilot.agent.domain.AgentRunStatus;
import com.repopilot.agent.domain.AgentStep;
import com.repopilot.agent.domain.AgentStepStatus;
import com.repopilot.agent.domain.AgentTask;
import com.repopilot.agent.domain.AgentTaskStatus;
import com.repopilot.agent.domain.AgentTaskType;
import com.repopilot.agent.repository.AgentRunRepository;
import com.repopilot.agent.repository.AgentStepRepository;
import com.repopilot.agent.repository.AgentTaskRepository;
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

@SpringBootTest(properties = "repopilot.agent-worker.callback-token=test-worker-callback-token")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AgentWorkerCallbackControllerIntegrationTest {

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
    private AgentStepRepository agentStepRepository;

    private String email;
    private Project project;
    private AgentTask task;
    private AgentRun run;

    @BeforeEach
    void setUp() {
        email = "worker-callback-" + UUID.randomUUID() + "@example.test";
        User user = userRepository.save(new User(email, "hash", "Worker Callback", "USER"));
        project = projectRepository.save(new Project(user, "file:///worker-callback", "worker/callback", "main"));
        project.setLocalPath("/workspace/repos/worker-callback/source");
        project = projectRepository.save(project);
        task = agentTaskRepository.save(new AgentTask(
                project,
                user,
                AgentTaskType.FEATURE,
                "Worker callback task",
                "Record worker step evidence."
        ));
        run = agentRunRepository.save(new AgentRun(task));
        task.setCurrentRun(run);
        task = agentTaskRepository.save(task);
    }

    @AfterEach
    void tearDown() {
        if (run != null && run.getId() != null) {
            agentStepRepository.deleteAll(agentStepRepository.findByAgentRunIdOrderByStartedAtAsc(run.getId()));
        }
        if (task != null && task.getId() != null) {
            agentTaskRepository.findById(task.getId()).ifPresent(savedTask -> {
                savedTask.setCurrentRun(null);
                agentTaskRepository.save(savedTask);
            });
        }
        if (run != null && run.getId() != null) {
            agentRunRepository.findById(run.getId()).ifPresent(agentRunRepository::delete);
        }
        if (task != null && task.getId() != null) {
            agentTaskRepository.findById(task.getId()).ifPresent(agentTaskRepository::delete);
        }
        if (project != null && project.getId() != null) {
            projectRepository.findById(project.getId()).ifPresent(projectRepository::delete);
        }
        userRepository.findByEmail(email).ifPresent(userRepository::delete);
    }

    @Test
    void recordStepRequiresCallbackTokenAndPersistsStepEvidence() throws Exception {
        Map<String, Object> request = Map.of(
                "step_name", "worker_plan_task",
                "status", "SUCCESS",
                "input", Map.of("taskId", task.getId(), "source", "agent-worker"),
                "output", Map.of("summary", "Worker generated a plan", "graph_nodes", List.of("plan_task")),
                "error_message", ""
        );

        mockMvc.perform(post("/api/internal/agent-worker/runs/{runId}/steps", run.getId())
                        .header(AgentWorkerCallbackController.CALLBACK_TOKEN_HEADER, "test-worker-callback-token")
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.stepName").value("worker_plan_task"))
                .andExpect(jsonPath("$.data.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.outputJson").value(org.hamcrest.Matchers.containsString("Worker generated a plan")));

        List<AgentStep> steps = agentStepRepository.findByAgentRunIdOrderByStartedAtAsc(run.getId());
        assertThat(steps)
                .hasSize(1)
                .allSatisfy(step -> {
                    assertThat(step.getStepName()).isEqualTo("worker_plan_task");
                    assertThat(step.getStatus()).isEqualTo(AgentStepStatus.SUCCESS);
                    assertThat(jsonNode(step.getInputJson()).path("source").asText()).isEqualTo("agent-worker");
                    assertThat(jsonNode(step.getOutputJson()).path("summary").asText()).isEqualTo("Worker generated a plan");
                    assertThat(step.getFinishedAt()).isNotNull();
                });
    }

    @Test
    void recordStepRejectsInvalidCallbackTokenBeforeJwtAuthentication() throws Exception {
        Map<String, Object> request = Map.of(
                "step_name", "worker_plan_task",
                "status", "SUCCESS",
                "input", Map.of(),
                "output", Map.of("summary", "Worker generated a plan")
        );

        mockMvc.perform(post("/api/internal/agent-worker/runs/{runId}/steps", run.getId())
                        .header(AgentWorkerCallbackController.CALLBACK_TOKEN_HEADER, "wrong-token")
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("AGENT_WORKER_CALLBACK_FORBIDDEN"));

        assertThat(agentStepRepository.findByAgentRunIdOrderByStartedAtAsc(run.getId())).isEmpty();
    }

    @Test
    void updateStatusRequiresCallbackTokenAndPersistsTaskAndRunStatus() throws Exception {
        Map<String, Object> request = Map.of(
                "task_status", "WAITING_HUMAN_APPROVAL",
                "run_status", "SUCCESS",
                "stream_message", "Worker 已进入人工审批",
                "complete_stream", true
        );

        mockMvc.perform(post("/api/internal/agent-worker/runs/{runId}/status", run.getId())
                        .header(AgentWorkerCallbackController.CALLBACK_TOKEN_HEADER, "test-worker-callback-token")
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.taskId").value(task.getId()))
                .andExpect(jsonPath("$.data.taskStatus").value("WAITING_HUMAN_APPROVAL"))
                .andExpect(jsonPath("$.data.runId").value(run.getId()))
                .andExpect(jsonPath("$.data.runStatus").value("SUCCESS"))
                .andExpect(jsonPath("$.data.streamCompleted").value(true));

        AgentTask savedTask = agentTaskRepository.findById(task.getId()).orElseThrow();
        AgentRun savedRun = agentRunRepository.findById(run.getId()).orElseThrow();
        assertThat(savedTask.getStatus()).isEqualTo(AgentTaskStatus.WAITING_HUMAN_APPROVAL);
        assertThat(savedRun.getStatus()).isEqualTo(AgentRunStatus.SUCCESS);
        assertThat(savedRun.getFinishedAt()).isNotNull();
        assertThat(savedRun.getErrorMessage()).isNull();
    }

    @Test
    void updateStatusRejectsEmptyStatusPayload() throws Exception {
        mockMvc.perform(post("/api/internal/agent-worker/runs/{runId}/status", run.getId())
                        .header(AgentWorkerCallbackController.CALLBACK_TOKEN_HEADER, "test-worker-callback-token")
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("stream_message", "nothing to update"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("AGENT_WORKER_STATUS_EMPTY"));
    }

    @Test
    void updateStatusRejectsInvalidCallbackTokenBeforeJwtAuthentication() throws Exception {
        Map<String, Object> request = Map.of(
                "task_status", "FAILED_PATCH_GENERATION",
                "run_status", "FAILED",
                "error_message", "worker failed"
        );

        mockMvc.perform(post("/api/internal/agent-worker/runs/{runId}/status", run.getId())
                        .header(AgentWorkerCallbackController.CALLBACK_TOKEN_HEADER, "wrong-token")
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("AGENT_WORKER_CALLBACK_FORBIDDEN"));

        assertThat(agentTaskRepository.findById(task.getId()).orElseThrow().getStatus())
                .isEqualTo(AgentTaskStatus.CREATED);
        assertThat(agentRunRepository.findById(run.getId()).orElseThrow().getStatus())
                .isEqualTo(AgentRunStatus.RUNNING);
    }

    private JsonNode jsonNode(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }
}
