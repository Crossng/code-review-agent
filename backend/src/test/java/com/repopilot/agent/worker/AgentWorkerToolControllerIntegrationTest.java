package com.repopilot.agent.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.nio.file.Files;
import java.nio.file.Path;
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
import com.repopilot.user.domain.User;
import com.repopilot.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(properties = "repopilot.agent-worker.callback-token=test-worker-tool-token")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AgentWorkerToolControllerIntegrationTest {

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

    @TempDir
    private Path workspaceRoot;

    private String email;
    private Project project;
    private AgentTask task;
    private AgentRun run;

    @BeforeEach
    void setUp() throws Exception {
        Files.createDirectories(workspaceRoot.resolve("src/main/java/com/example/demo/user"));
        Files.writeString(
                workspaceRoot.resolve("src/main/java/com/example/demo/user/UserController.java"),
                """
                        package com.example.demo.user;

                        public class UserController {
                            public String listUsers() {
                                return "ok";
                            }
                        }
                        """
        );
        Files.createDirectories(workspaceRoot.resolve(".git"));
        Files.writeString(workspaceRoot.resolve(".git/config"), "private git metadata");

        email = "worker-tool-" + UUID.randomUUID() + "@example.test";
        User user = userRepository.save(new User(email, "hash", "Worker Tool", "USER"));
        project = projectRepository.save(new Project(user, "file:///worker-tool", "worker/tool", "main"));
        project.setLocalPath(workspaceRoot.toString());
        project = projectRepository.save(project);
        task = agentTaskRepository.save(new AgentTask(
                project,
                user,
                AgentTaskType.FEATURE,
                "给 User 模块新增分页查询接口",
                "需要先读取 Controller 和检索 User 相关代码。"
        ));
        run = agentRunRepository.save(new AgentRun(task));
        task.setCurrentRun(run);
        task = agentTaskRepository.save(task);
    }

    @AfterEach
    void tearDown() {
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
    void loadRunContextRequiresWorkerTokenAndReturnsRunScopedContext() throws Exception {
        mockMvc.perform(get("/api/internal/agent-worker/runs/{runId}/context", run.getId())
                        .header(AgentWorkerCallbackController.CALLBACK_TOKEN_HEADER, "test-worker-tool-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.runId").value(run.getId()))
                .andExpect(jsonPath("$.data.taskId").value(task.getId()))
                .andExpect(jsonPath("$.data.projectId").value(project.getId()))
                .andExpect(jsonPath("$.data.repoFullName").value("worker/tool"))
                .andExpect(jsonPath("$.data.title").value("给 User 模块新增分页查询接口"));
    }

    @Test
    void loadRunContextRejectsInvalidWorkerTokenBeforeJwtAuthentication() throws Exception {
        mockMvc.perform(get("/api/internal/agent-worker/runs/{runId}/context", run.getId())
                        .header(AgentWorkerCallbackController.CALLBACK_TOKEN_HEADER, "wrong-token"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("AGENT_WORKER_CALLBACK_FORBIDDEN"));
    }

    @Test
    void listFilesAndReadFileStayInsideRunProjectWorkspace() throws Exception {
        MvcResult filesResult = mockMvc.perform(get("/api/internal/agent-worker/runs/{runId}/project/files", run.getId())
                        .header(AgentWorkerCallbackController.CALLBACK_TOKEN_HEADER, "test-worker-tool-token")
                        .param("maxDepth", "8"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn();
        JsonNode files = objectMapper.readTree(filesResult.getResponse().getContentAsString()).path("data");
        List<String> paths = new ArrayList<>();
        files.forEach(file -> paths.add(file.path("path").asText()));
        assertThat(paths)
                .contains("src/main/java/com/example/demo/user/UserController.java")
                .doesNotContain(".git/config");

        mockMvc.perform(get("/api/internal/agent-worker/runs/{runId}/project/file", run.getId())
                        .header(AgentWorkerCallbackController.CALLBACK_TOKEN_HEADER, "test-worker-tool-token")
                        .param("path", "src/main/java/com/example/demo/user/UserController.java"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.path").value("src/main/java/com/example/demo/user/UserController.java"))
                .andExpect(jsonPath("$.data.content").value(org.hamcrest.Matchers.containsString("class UserController")));
    }

    @Test
    void readFileRejectsUnsafeRelativePath() throws Exception {
        mockMvc.perform(get("/api/internal/agent-worker/runs/{runId}/project/file", run.getId())
                        .header(AgentWorkerCallbackController.CALLBACK_TOKEN_HEADER, "test-worker-tool-token")
                        .param("path", "../secret.txt"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("AGENT_WORKER_FILE_PATH_INVALID"));
    }

    @Test
    void searchCodeUsesRunProjectScope() throws Exception {
        mockMvc.perform(get("/api/internal/agent-worker/runs/{runId}/project/search", run.getId())
                        .header(AgentWorkerCallbackController.CALLBACK_TOKEN_HEADER, "test-worker-tool-token")
                        .param("query", "User Controller")
                        .param("limit", "4"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.query").value("User Controller"))
                .andExpect(jsonPath("$.data.limit").value(4))
                .andExpect(jsonPath("$.data.results").isArray());
    }
}
