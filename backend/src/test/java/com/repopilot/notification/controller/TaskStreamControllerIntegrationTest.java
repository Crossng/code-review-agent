package com.repopilot.notification.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.http.MediaType.TEXT_EVENT_STREAM;
import static org.springframework.http.MediaType.TEXT_EVENT_STREAM_VALUE;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.repopilot.agent.domain.AgentRun;
import com.repopilot.agent.domain.AgentStep;
import com.repopilot.agent.domain.AgentStepStatus;
import com.repopilot.agent.domain.AgentTask;
import com.repopilot.agent.domain.AgentTaskStatus;
import com.repopilot.agent.domain.AgentTaskType;
import com.repopilot.agent.repository.AgentRunRepository;
import com.repopilot.agent.repository.AgentStepRepository;
import com.repopilot.agent.repository.AgentTaskRepository;
import com.repopilot.notification.service.TaskStreamService;
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
class TaskStreamControllerIntegrationTest {

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

    @Autowired
    private TaskStreamService taskStreamService;

    private String ownerEmail;
    private String otherEmail;
    private Project project;
    private AgentTask task;
    private AgentRun run;

    @BeforeEach
    void setUp() {
        ownerEmail = "stream-owner-" + UUID.randomUUID() + "@example.test";
        otherEmail = "stream-other-" + UUID.randomUUID() + "@example.test";
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
        userRepository.findByEmail(ownerEmail).ifPresent(userRepository::delete);
        userRepository.findByEmail(otherEmail).ifPresent(userRepository::delete);
    }

    @Test
    void streamReturnsTaskAndStepSnapshotEventsForOwnerOnly() throws Exception {
        String ownerToken = register(ownerEmail);
        String otherToken = register(otherEmail);
        User owner = userRepository.findByEmail(ownerEmail).orElseThrow();
        createTaskRunAndSteps(owner);

        MvcResult result = mockMvc.perform(get("/api/agent/tasks/{taskId}/stream", task.getId())
                        .header(AUTHORIZATION, bearer(ownerToken))
                        .accept(TEXT_EVENT_STREAM, APPLICATION_JSON))
                .andExpect(request().asyncStarted())
                .andReturn();
        MvcResult dispatchedResult = mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andReturn();

        String stream = dispatchedResult.getResponse().getContentAsString();
        assertThat(dispatchedResult.getResponse().getContentType()).contains(TEXT_EVENT_STREAM_VALUE);
        assertThat(stream)
                .contains("event:task_snapshot")
                .contains("\"eventType\":\"TASK_SNAPSHOT\"")
                .contains("\"taskStatus\":\"WAITING_HUMAN_APPROVAL\"")
                .contains("\"runStatus\":\"SUCCESS\"")
                .contains("event:step_snapshot")
                .contains("\"stepName\":\"plan_task\"")
                .contains("\"stepName\":\"run_tests\"")
                .contains("event:stream_complete")
                .contains("\"eventType\":\"STREAM_COMPLETE\"");

        mockMvc.perform(get("/api/agent/tasks/{taskId}/stream", task.getId())
                        .header(AUTHORIZATION, bearer(otherToken))
                        .accept(TEXT_EVENT_STREAM, APPLICATION_JSON))
                .andExpect(status().isForbidden());
    }

    @Test
    void streamReceivesLiveStepAndCompletionEvents() throws Exception {
        String ownerToken = register(ownerEmail);
        User owner = userRepository.findByEmail(ownerEmail).orElseThrow();
        createRunningTaskRun(owner);

        MvcResult result = mockMvc.perform(get("/api/agent/tasks/{taskId}/stream", task.getId())
                        .header(AUTHORIZATION, bearer(ownerToken))
                        .accept(TEXT_EVENT_STREAM, APPLICATION_JSON))
                .andExpect(request().asyncStarted())
                .andReturn();

        AgentStep step = agentStepRepository.save(new AgentStep(run, "review_patch", AgentStepStatus.SUCCESS, "{}", "{}"));
        taskStreamService.publishStepRecorded(task, run, step);
        task.setStatus(AgentTaskStatus.WAITING_HUMAN_APPROVAL);
        task = agentTaskRepository.save(task);
        run.markSuccess();
        run = agentRunRepository.save(run);
        taskStreamService.publishTaskUpdated(task, run);
        taskStreamService.publishStreamComplete(task, run, "Agent run reached human approval");

        MvcResult dispatchedResult = mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andReturn();
        String stream = dispatchedResult.getResponse().getContentAsString();
        assertThat(stream)
                .contains("event:task_snapshot")
                .contains("\"taskStatus\":\"GENERATING_PATCH\"")
                .contains("event:step_recorded")
                .contains("\"eventType\":\"STEP_RECORDED\"")
                .contains("\"stepName\":\"review_patch\"")
                .contains("event:task_updated")
                .contains("\"runStatus\":\"SUCCESS\"")
                .contains("event:stream_complete")
                .contains("\"message\":\"Agent run reached human approval\"");
    }

    private void createTaskRunAndSteps(User owner) {
        project = projectRepository.save(new Project(owner, "file:///tmp/demo.git", "example/demo", "main"));
        task = agentTaskRepository.save(new AgentTask(
                project,
                owner,
                AgentTaskType.FEATURE,
                "Stream task",
                "Verify SSE task snapshots"
        ));
        run = agentRunRepository.save(new AgentRun(task));
        run.markSuccess();
        run = agentRunRepository.save(run);
        task.setCurrentRun(run);
        task.setStatus(AgentTaskStatus.WAITING_HUMAN_APPROVAL);
        task = agentTaskRepository.save(task);
        agentStepRepository.save(new AgentStep(run, "plan_task", AgentStepStatus.SUCCESS, "{}", "{}"));
        agentStepRepository.save(new AgentStep(run, "run_tests", AgentStepStatus.SUCCESS, "{}", "{}"));
    }

    private void createRunningTaskRun(User owner) {
        project = projectRepository.save(new Project(owner, "file:///tmp/demo.git", "example/demo", "main"));
        task = agentTaskRepository.save(new AgentTask(
                project,
                owner,
                AgentTaskType.FEATURE,
                "Live stream task",
                "Verify live SSE task events"
        ));
        run = agentRunRepository.save(new AgentRun(task));
        task.setCurrentRun(run);
        task.setStatus(AgentTaskStatus.GENERATING_PATCH);
        task = agentTaskRepository.save(task);
    }

    private String register(String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(APPLICATION_JSON)
                        .content(json(Map.of(
                                "email", email,
                                "password", "password123",
                                "displayName", "Stream User"
                        ))))
                .andExpect(status().isOk())
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
