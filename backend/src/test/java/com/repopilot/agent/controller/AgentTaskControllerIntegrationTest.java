package com.repopilot.agent.controller;

import static org.hamcrest.Matchers.containsString;
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
import com.repopilot.project.domain.Project;
import com.repopilot.project.domain.ProjectStatus;
import com.repopilot.project.repository.ProjectRepository;
import com.repopilot.user.domain.User;
import com.repopilot.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AgentTaskControllerIntegrationTest {

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
    private JdbcTemplate jdbcTemplate;

    private String ownerEmail;
    private String otherEmail;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString();
        ownerEmail = "task-filter-owner-" + suffix + "@example.test";
        otherEmail = "task-filter-other-" + suffix + "@example.test";
    }

    @AfterEach
    void tearDown() {
        cleanupUser(ownerEmail);
        cleanupUser(otherEmail);
    }

    @Test
    void listTasksFiltersByCurrentUserProjectStatusTypeAndQuery() throws Exception {
        String ownerToken = register(ownerEmail);
        register(otherEmail);
        User owner = userRepository.findByEmail(ownerEmail).orElseThrow();
        User other = userRepository.findByEmail(otherEmail).orElseThrow();

        Project apiProject = project(owner, "owner/api", ProjectStatus.READY);
        Project billingProject = project(owner, "owner/billing", ProjectStatus.READY);
        Project otherProject = project(other, "other/api", ProjectStatus.READY);

        task(owner, apiProject, AgentTaskType.FEATURE, AgentTaskStatus.CREATED, "Add User pagination API", "Build a page query for users.");
        task(owner, apiProject, AgentTaskType.BUGFIX, AgentTaskStatus.FAILED_TEST, "Fix User id validation bug", "Guard negative ids.");
        task(owner, billingProject, AgentTaskType.DOC, AgentTaskStatus.DONE, "Document billing workflow", "Write billing notes.");
        task(other, otherProject, AgentTaskType.FEATURE, AgentTaskStatus.CREATED, "Add User pagination API", "Other user task.");

        mockMvc.perform(get("/api/agent/tasks")
                        .header(AUTHORIZATION, bearer(ownerToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.length()").value(3));

        mockMvc.perform(get("/api/agent/tasks")
                        .queryParam("projectId", apiProject.getId().toString())
                        .header(AUTHORIZATION, bearer(ownerToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2));

        mockMvc.perform(get("/api/agent/tasks")
                        .queryParam("status", "FAILED_TEST")
                        .header(AUTHORIZATION, bearer(ownerToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].title").value("Fix User id validation bug"));

        mockMvc.perform(get("/api/agent/tasks")
                        .queryParam("taskType", "DOC")
                        .header(AUTHORIZATION, bearer(ownerToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].title").value("Document billing workflow"));

        mockMvc.perform(get("/api/agent/tasks")
                        .queryParam("query", "PaGiNaTiOn")
                        .header(AUTHORIZATION, bearer(ownerToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].title").value("Add User pagination API"));

        mockMvc.perform(get("/api/agent/tasks")
                        .queryParam("projectId", apiProject.getId().toString())
                        .queryParam("status", "CREATED")
                        .queryParam("taskType", "FEATURE")
                        .queryParam("query", "page")
                        .header(AUTHORIZATION, bearer(ownerToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].projectId").value(apiProject.getId()))
                .andExpect(jsonPath("$.data[0].title").value("Add User pagination API"));
    }

    @Test
    void runReportSummarizesCurrentRunStepEvidenceAndIsOwnerScoped() throws Exception {
        String ownerToken = register(ownerEmail);
        String otherToken = register(otherEmail);
        User owner = userRepository.findByEmail(ownerEmail).orElseThrow();
        Project project = project(owner, "owner/api", ProjectStatus.READY);
        AgentTask task = task(
                owner,
                project,
                AgentTaskType.FEATURE,
                AgentTaskStatus.WAITING_HUMAN_APPROVAL,
                "Add User pagination API",
                "Build a page query for users."
        );
        AgentRun run = new AgentRun(task);
        run.markSuccess();
        run = agentRunRepository.save(run);
        task.setCurrentRun(run);
        agentTaskRepository.save(task);

        agentStepRepository.save(new AgentStep(
                run,
                "plan_task",
                AgentStepStatus.SUCCESS,
                json(Map.of("title", task.getTitle())),
                json(Map.of(
                        "summary", "为任务准备实现上下文：Add User pagination API",
                        "searchQueries", List.of("Add User pagination API", "pagination"),
                        "steps", List.of(Map.of(
                                "order", 1,
                                "title", "检索仓库上下文",
                                "reason", "检索已索引代码片段"
                        ))
                ))
        ));
        agentStepRepository.save(new AgentStep(
                run,
                "retrieve_context",
                AgentStepStatus.SUCCESS,
                json(Map.of("queries", List.of("pagination"))),
                json(Map.of(
                        "queries", List.of("pagination"),
                        "resultCountByQuery", Map.of("pagination", 1),
                        "results", List.of(Map.of(
                                "chunkId", 10,
                                "filePath", "src/main/java/com/example/demo/user/UserController.java",
                                "qualifiedName", "UserController#listUsers",
                                "startLine", 12,
                                "endLine", 20,
                                "summary", "现有 User 列表接口"
                        ))
                ))
        ));
        agentStepRepository.save(new AgentStep(
                run,
                "generate_patch",
                AgentStepStatus.SUCCESS,
                json(Map.of("mode", "SPRING_RECIPE_CODER_WITH_RETRIEVAL_PLAN_FALLBACK")),
                json(Map.of(
                        "patchId", 42,
                        "status", "APPLIED",
                        "baseBranch", "main",
                        "targetBranch", "repopilot/task-1",
                        "summary", "新增 GET /api/users/page",
                        "generationMode", "SPRING_USER_PAGINATION_RECIPE"
                ))
        ));
        agentStepRepository.save(new AgentStep(
                run,
                "validate_patch_safety",
                AgentStepStatus.SUCCESS,
                json(Map.of("patchId", 42)),
                json(Map.of("safe", true, "reasons", List.of("所有 diff 路径都在仓库内")))
        ));
        agentStepRepository.save(new AgentStep(
                run,
                "run_tests",
                AgentStepStatus.SUCCESS,
                json(Map.of("patchId", 42, "command", "mvn -q test")),
                json(Map.of(
                        "testRunId", 5,
                        "patchId", 42,
                        "status", "PASSED",
                        "command", "mvn -q test",
                        "exitCode", 0,
                        "durationMs", 1200,
                        "logExcerpt", "测试通过"
                ))
        ));
        agentStepRepository.save(new AgentStep(
                run,
                "review_patch",
                AgentStepStatus.SUCCESS,
                json(Map.of("patchId", 42, "testRunId", 5)),
                json(Map.of(
                        "riskLevel", "LOW",
                        "summary", "没有自动审查发现。",
                        "findings", List.of()
                ))
        ));
        agentStepRepository.save(new AgentStep(
                run,
                "waiting_human_approval",
                AgentStepStatus.PENDING,
                json(Map.of("patchId", 42, "status", "APPLIED")),
                null
        ));

        mockMvc.perform(get("/api/agent/tasks/{id}/run-report", task.getId())
                        .header(AUTHORIZATION, bearer(ownerToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.taskId").value(task.getId()))
                .andExpect(jsonPath("$.data.runId").value(run.getId()))
                .andExpect(jsonPath("$.data.sections.length()").value(7))
                .andExpect(jsonPath("$.data.sections[0].key").value("planner"))
                .andExpect(jsonPath("$.data.sections[0].title").value("任务规划"))
                .andExpect(jsonPath("$.data.sections[1].key").value("retrieval"))
                .andExpect(jsonPath("$.data.sections[1].facts[0]").value("去重代码片段：1"))
                .andExpect(jsonPath("$.data.sections[2].facts[2]").value("SPRING_USER_PAGINATION_RECIPE"))
                .andExpect(jsonPath("$.data.sections[4].summary").value("测试通过"))
                .andExpect(jsonPath("$.data.markdown").value(containsString("# RepoPilot Agent 运行报告")))
                .andExpect(jsonPath("$.data.markdown").value(containsString("## 任务规划")))
                .andExpect(jsonPath("$.data.markdown").value(containsString("## 检索到的代码上下文")))
                .andExpect(jsonPath("$.data.markdown").value(containsString("SPRING_USER_PAGINATION_RECIPE")));

        MvcResult snapshotResult = mockMvc.perform(post("/api/agent/tasks/{id}/run-report/snapshots", task.getId())
                        .header(AUTHORIZATION, bearer(ownerToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.taskId").value(task.getId()))
                .andExpect(jsonPath("$.data.runId").value(run.getId()))
                .andExpect(jsonPath("$.data.sectionCount").value(7))
                .andExpect(jsonPath("$.data.markdown").value(containsString("# RepoPilot Agent 运行报告")))
                .andReturn();
        long snapshotId = objectMapper.readTree(snapshotResult.getResponse().getContentAsString())
                .path("data")
                .path("id")
                .asLong();

        mockMvc.perform(get("/api/agent/tasks/{id}/run-report/snapshots", task.getId())
                        .header(AUTHORIZATION, bearer(ownerToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(snapshotId))
                .andExpect(jsonPath("$.data[0].sectionCount").value(7));

        mockMvc.perform(get("/api/agent/tasks/{id}/run-report/snapshots/{snapshotId}", task.getId(), snapshotId)
                        .header(AUTHORIZATION, bearer(ownerToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(snapshotId))
                .andExpect(jsonPath("$.data.markdown").value(containsString("## 沙箱测试结果")))
                .andExpect(jsonPath("$.data.markdown").value(containsString("## 自动补丁审查")));

        mockMvc.perform(get("/api/agent/tasks/{id}/run-report", task.getId())
                        .header(AUTHORIZATION, bearer(otherToken)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AGENT_TASK_FORBIDDEN"));

        mockMvc.perform(get("/api/agent/tasks/{id}/run-report/snapshots/{snapshotId}", task.getId(), snapshotId)
                        .header(AUTHORIZATION, bearer(otherToken)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AGENT_TASK_FORBIDDEN"));
    }

    private String register(String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(APPLICATION_JSON)
                        .content(json(Map.of(
                                "email", email,
                                "password", "password123",
                                "displayName", "Task Filter User"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data").path("token").asText();
    }

    private Project project(User owner, String name, ProjectStatus status) {
        Project project = new Project(owner, "https://github.com/" + name + ".git", name, "main");
        project.setStatus(status);
        return projectRepository.save(project);
    }

    private AgentTask task(
            User owner,
            Project project,
            AgentTaskType type,
            AgentTaskStatus status,
            String title,
            String description
    ) {
        AgentTask task = new AgentTask(project, owner, type, title, description);
        task.setStatus(status);
        return agentTaskRepository.save(task);
    }

    private void cleanupUser(String email) {
        jdbcTemplate.update("""
                delete from agent_step
                where agent_run_id in (
                    select run.id
                    from agent_run run
                    join agent_task task on task.id = run.agent_task_id
                    join app_user app on app.id = task.user_id
                    where app.email = ?
                )
                """, email);
        jdbcTemplate.update("""
                update agent_task
                set current_run_id = null
                where user_id in (select id from app_user where email = ?)
                """, email);
        jdbcTemplate.update("""
                delete from agent_run
                where agent_task_id in (
                    select task.id
                    from agent_task task
                    join app_user app on app.id = task.user_id
                    where app.email = ?
                )
                """, email);
        jdbcTemplate.update("""
                delete from agent_task
                where user_id in (select id from app_user where email = ?)
                """, email);
        jdbcTemplate.update("""
                delete from project
                where owner_user_id in (select id from app_user where email = ?)
                """, email);
        userRepository.findByEmail(email).ifPresent(userRepository::delete);
    }

    private String json(Object value) throws IOException {
        return objectMapper.writeValueAsString(value);
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }
}
