package com.repopilot.project.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProjectControllerIntegrationTest {

    private static final Path TEST_WORKSPACE = Path.of("../target/repopilot-http-it-workspace")
            .toAbsolutePath()
            .normalize();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProjectRepository projectRepository;

    private String email;
    private final List<String> createdEmails = new ArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        createdEmails.clear();
        email = "project-it-" + UUID.randomUUID() + "@example.test";
        cleanWorkspace();
    }

    @AfterEach
    void tearDown() throws IOException {
        for (String createdEmail : createdEmails) {
            cleanupUser(createdEmail);
        }
        cleanWorkspace();
    }

    @Test
    void listProjectsFiltersByCurrentUserStatusAndQuery() throws Exception {
        String suffix = UUID.randomUUID().toString();
        String ownerEmail = "project-filter-owner-" + suffix + "@example.test";
        String otherEmail = "project-filter-other-" + suffix + "@example.test";
        String ownerToken = register(ownerEmail, "Project Filter Owner");
        register(otherEmail, "Project Filter Other");
        User owner = userRepository.findByEmail(ownerEmail).orElseThrow();
        User other = userRepository.findByEmail(otherEmail).orElseThrow();

        project(owner, "owner/demo-service", "https://github.com/owner/demo-service.git", ProjectStatus.READY);
        project(owner, "owner/billing-service", "https://github.com/owner/billing-service.git", ProjectStatus.FAILED);
        project(owner, "owner/internal-tools", "https://git.example.test/owner/internal-tools.git", ProjectStatus.CREATED);
        project(other, "other/demo-service", "https://github.com/other/demo-service.git", ProjectStatus.READY);

        mockMvc.perform(get("/api/projects")
                        .header(AUTHORIZATION, bearer(ownerToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.length()").value(3));

        mockMvc.perform(get("/api/projects")
                        .queryParam("status", "READY")
                        .header(AUTHORIZATION, bearer(ownerToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].repoFullName").value("owner/demo-service"));

        mockMvc.perform(get("/api/projects")
                        .queryParam("query", "DeMo")
                        .header(AUTHORIZATION, bearer(ownerToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].repoFullName").value("owner/demo-service"));

        mockMvc.perform(get("/api/projects")
                        .queryParam("query", "git.example.test/OWNER/internal")
                        .header(AUTHORIZATION, bearer(ownerToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].repoFullName").value("owner/internal-tools"));

        mockMvc.perform(get("/api/projects")
                        .queryParam("status", "FAILED")
                        .queryParam("query", "billing")
                        .header(AUTHORIZATION, bearer(ownerToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].repoFullName").value("owner/billing-service"));
    }

    @Test
    void filesSymbolsAndSearchExposeIndexedRepositoryData() throws Exception {
        String token = register();
        long projectId = createProject(token);

        JsonNode clone = data(mockMvc.perform(post("/api/projects/{id}/clone", projectId)
                        .header(AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("READY"))
                .andExpect(jsonPath("$.data.javaFileCount", greaterThan(0)))
                .andReturn());
        writeParameterController(Path.of(clone.path("localPath").asText()));

        mockMvc.perform(post("/api/projects/{id}/index", projectId)
                        .header(AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.javaFileCount", greaterThan(0)))
                .andExpect(jsonPath("$.data.symbolCount", greaterThan(0)))
                .andExpect(jsonPath("$.data.chunkCount", greaterThan(0)));

        JsonNode files = data(mockMvc.perform(get("/api/projects/{id}/files", projectId)
                        .param("maxDepth", "10")
                        .header(AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn());
        assertThat(files).anySatisfy(file -> {
            assertThat(file.path("path").asText()).isEqualTo("pom.xml");
            assertThat(file.path("type").asText()).isEqualTo("FILE");
            assertThat(file.path("size").asLong()).isGreaterThan(0);
        });
        assertThat(files).anySatisfy(file -> {
            assertThat(file.path("path").asText()).isEqualTo("src/main/java/com/example/demo/user/UserService.java");
            assertThat(file.path("type").asText()).isEqualTo("FILE");
        });
        assertThat(files).noneSatisfy(file ->
                assertThat(file.path("path").asText()).startsWith(".git")
        );

        JsonNode serviceSymbols = data(mockMvc.perform(get("/api/projects/{id}/symbols", projectId)
                        .param("type", "SERVICE")
                        .header(AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn());
        assertThat(serviceSymbols).anySatisfy(symbol -> {
            assertThat(symbol.path("symbolType").asText()).isEqualTo("SERVICE");
            assertThat(symbol.path("name").asText()).isEqualTo("UserService");
            assertThat(symbol.path("qualifiedName").asText()).isEqualTo("com.example.demo.user.UserService");
            assertThat(symbol.path("filePath").asText()).isEqualTo("src/main/java/com/example/demo/user/UserService.java");
            assertThat(symbol.path("startLine").asInt()).isGreaterThan(0);
        });

        JsonNode controllerApiList = data(mockMvc.perform(get("/api/projects/{id}/controller-apis", projectId)
                        .header(AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.filteredCount").value(6))
                .andExpect(jsonPath("$.data.riskSummary.total").value(6))
                .andExpect(jsonPath("$.data.riskSummary.byLevel.HIGH").value(1))
                .andExpect(jsonPath("$.data.riskSummary.byLevel.MEDIUM").value(3))
                .andExpect(jsonPath("$.data.riskSummary.byLevel.LOW").value(2))
                .andExpect(jsonPath("$.data.riskSummary.byLevel.NONE").value(0))
                .andReturn());
        assertThat(controllerApiList.path("filters").path("riskLevel").isNull()).isTrue();
        assertThat(controllerApiList.path("filters").path("riskCode").isNull()).isTrue();
        assertThat(controllerApiList.path("riskCodes")).extracting(JsonNode::asText)
                .containsExactly(
                        "BODY_FIELDS_WITHOUT_CONSTRAINTS",
                        "BODY_TYPE_WITHOUT_CONSTRAINTS",
                        "BODY_WITHOUT_VALIDATION",
                        "NO_SECURITY_ANNOTATION",
                        "OPTIONAL_REQUEST_BODY",
                        "QUERY_PARAMETER_WITHOUT_BOUNDS"
                );
        JsonNode controllerApis = controllerApiList.path("items");
        assertThat(controllerApis).hasSize(6);

        JsonNode filteredControllerApiList = data(mockMvc.perform(get("/api/projects/{id}/controller-apis", projectId)
                        .param("riskLevel", "MEDIUM")
                        .param("riskCode", "NO_SECURITY_ANNOTATION")
                        .header(AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.filteredCount").value(2))
                .andExpect(jsonPath("$.data.riskSummary.total").value(6))
                .andExpect(jsonPath("$.data.riskSummary.byLevel.MEDIUM").value(3))
                .andExpect(jsonPath("$.data.filters.riskLevel").value("MEDIUM"))
                .andExpect(jsonPath("$.data.filters.riskCode").value("NO_SECURITY_ANNOTATION"))
                .andReturn());
        assertThat(filteredControllerApiList.path("riskCodes")).extracting(JsonNode::asText)
                .contains("BODY_WITHOUT_VALIDATION", "NO_SECURITY_ANNOTATION", "QUERY_PARAMETER_WITHOUT_BOUNDS");
        JsonNode filteredControllerApis = filteredControllerApiList.path("items");
        assertThat(filteredControllerApis).hasSize(2);
        assertThat(filteredControllerApis).allSatisfy(api -> {
            assertThat(api.path("riskLevel").asText()).isEqualTo("MEDIUM");
            assertThat(api.path("riskHints")).anySatisfy(riskHint ->
                    assertThat(riskHint.path("code").asText()).isEqualTo("NO_SECURITY_ANNOTATION"));
        });

        JsonNode controllerApiDocs = data(mockMvc.perform(get("/api/projects/{id}/controller-apis/docs", projectId)
                        .param("riskLevel", "MEDIUM")
                        .param("riskCode", "NO_SECURITY_ANNOTATION")
                        .param("limit", "2")
                        .header(AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.projectId").value(projectId))
                .andExpect(jsonPath("$.data.routeCount").value(2))
                .andExpect(jsonPath("$.data.filteredCount").value(2))
                .andExpect(jsonPath("$.data.filters.riskLevel").value("MEDIUM"))
                .andExpect(jsonPath("$.data.filters.riskCode").value("NO_SECURITY_ANNOTATION"))
                .andReturn());
        assertThat(controllerApiDocs.path("repoFullName").asText()).contains("demo-spring-repo");
        assertThat(controllerApiDocs.path("markdown").asText())
                .contains(
                        "# Controller API docs:",
                        "demo-spring-repo",
                        "Current filters: Risk level: MEDIUM, Risk code: NO_SECURITY_ANNOTATION.",
                        "Routes included: 2 of 2.",
                        "## GET /api/users",
                        "`NO_SECURITY_ANNOTATION`",
                        "### Parameters",
                        "### Calls",
                        "### Risk hints"
                );

        JsonNode savedControllerApiDocsSnapshot = data(mockMvc.perform(post("/api/projects/{id}/controller-apis/docs/snapshots", projectId)
                        .param("riskLevel", "MEDIUM")
                        .param("riskCode", "NO_SECURITY_ANNOTATION")
                        .param("limit", "2")
                        .header(AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.projectId").value(projectId))
                .andExpect(jsonPath("$.data.routeCount").value(2))
                .andExpect(jsonPath("$.data.filteredCount").value(2))
                .andExpect(jsonPath("$.data.filters.riskLevel").value("MEDIUM"))
                .andExpect(jsonPath("$.data.filters.riskCode").value("NO_SECURITY_ANNOTATION"))
                .andReturn());
        long snapshotId = savedControllerApiDocsSnapshot.path("id").asLong();
        assertThat(snapshotId).isPositive();
        assertThat(savedControllerApiDocsSnapshot.path("markdown").asText())
                .contains("# Controller API docs:", "## GET /api/users", "`NO_SECURITY_ANNOTATION`");

        JsonNode controllerApiDocSnapshots = data(mockMvc.perform(get("/api/projects/{id}/controller-apis/docs/snapshots", projectId)
                        .param("limit", "5")
                        .header(AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(snapshotId))
                .andExpect(jsonPath("$.data[0].projectId").value(projectId))
                .andExpect(jsonPath("$.data[0].routeCount").value(2))
                .andExpect(jsonPath("$.data[0].filters.riskLevel").value("MEDIUM"))
                .andExpect(jsonPath("$.data[0].filters.riskCode").value("NO_SECURITY_ANNOTATION"))
                .andReturn());
        assertThat(controllerApiDocSnapshots.get(0).has("markdown")).isFalse();

        JsonNode loadedControllerApiDocsSnapshot = data(mockMvc.perform(get(
                                "/api/projects/{id}/controller-apis/docs/snapshots/{snapshotId}",
                                projectId,
                                snapshotId
                        )
                        .header(AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value(snapshotId))
                .andExpect(jsonPath("$.data.projectId").value(projectId))
                .andReturn());
        assertThat(loadedControllerApiDocsSnapshot.path("markdown").asText())
                .contains("# Controller API docs:", "Current filters: Risk level: MEDIUM", "## GET /api/users");

        mockMvc.perform(delete(
                                "/api/projects/{id}/controller-apis/docs/snapshots/{snapshotId}",
                                projectId,
                                snapshotId
                        )
                        .header(AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        mockMvc.perform(get("/api/projects/{id}/controller-apis/docs/snapshots", projectId)
                        .param("limit", "5")
                        .header(AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.length()").value(0));

        mockMvc.perform(get(
                                "/api/projects/{id}/controller-apis/docs/snapshots/{snapshotId}",
                                projectId,
                                snapshotId
                        )
                        .header(AUTHORIZATION, bearer(token)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("CONTROLLER_API_DOC_SNAPSHOT_NOT_FOUND"));

        long firstBulkSnapshotId = data(mockMvc.perform(post("/api/projects/{id}/controller-apis/docs/snapshots", projectId)
                        .param("riskLevel", "MEDIUM")
                        .param("riskCode", "NO_SECURITY_ANNOTATION")
                        .param("limit", "2")
                        .header(AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andReturn()).path("id").asLong();
        long secondBulkSnapshotId = data(mockMvc.perform(post("/api/projects/{id}/controller-apis/docs/snapshots", projectId)
                        .param("riskLevel", "MEDIUM")
                        .param("riskCode", "NO_SECURITY_ANNOTATION")
                        .param("limit", "2")
                        .header(AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andReturn()).path("id").asLong();
        assertThat(firstBulkSnapshotId).isPositive();
        assertThat(secondBulkSnapshotId).isNotEqualTo(firstBulkSnapshotId);

        mockMvc.perform(get("/api/projects/{id}/controller-apis/docs/snapshots", projectId)
                        .param("limit", "5")
                        .header(AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.length()").value(2));

        mockMvc.perform(delete("/api/projects/{id}/controller-apis/docs/snapshots", projectId)
                        .header(AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.deletedCount").value(2));

        mockMvc.perform(get("/api/projects/{id}/controller-apis/docs/snapshots", projectId)
                        .param("limit", "5")
                        .header(AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.length()").value(0));

        mockMvc.perform(get("/api/projects/{id}/controller-apis", projectId)
                        .param("riskLevel", "CRITICAL")
                        .header(AUTHORIZATION, bearer(token)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("CONTROLLER_API_INVALID_RISK_LEVEL"));

        assertThat(controllerApis).anySatisfy(api -> {
            assertThat(api.path("controllerName").asText()).isEqualTo("AuditController");
            assertThat(api.path("methodName").asText()).isEqualTo("readAudit");
            assertThat(api.path("httpMethod").asText()).isEqualTo("GET");
            assertThat(api.path("path").asText()).isEqualTo("/api/audit/{id}");
            assertThat(api.path("responseType").asText()).isEqualTo("String");
            assertThat(api.path("securityAnnotations")).extracting(JsonNode::asText)
                    .containsExactly("PreAuthorize", "Secured");
            assertThat(api.path("riskScore").asInt()).isEqualTo(10);
            assertThat(api.path("riskLevel").asText()).isEqualTo("LOW");
            assertThat(api.path("riskHints")).hasSize(1);
            JsonNode riskHint = api.path("riskHints").get(0);
            assertThat(riskHint.path("severity").asText()).isEqualTo("LOW");
            assertThat(riskHint.path("code").asText()).isEqualTo("QUERY_PARAMETER_WITHOUT_BOUNDS");
            assertThat(riskHint.path("message").asText()).contains("size", "lower", "upper");
            assertThat(riskHint.path("details")).extracting(JsonNode::asText)
                    .containsExactly("size: missing lower bound", "size: missing upper bound");
            assertThat(api.path("serviceCalls")).isEmpty();
            assertThat(api.path("parameters")).hasSize(3);
            assertThat(api.path("parameters")).anySatisfy(parameter -> {
                assertThat(parameter.path("name").asText()).isEqualTo("id");
                assertThat(parameter.path("source").asText()).isEqualTo("PATH");
                assertThat(parameter.path("type").asText()).isEqualTo("Long");
                assertThat(parameter.path("required").asBoolean()).isTrue();
                assertThat(parameter.path("defaultValue").isNull()).isTrue();
            });
            assertThat(api.path("parameters")).anySatisfy(parameter -> {
                assertThat(parameter.path("name").asText()).isEqualTo("size");
                assertThat(parameter.path("source").asText()).isEqualTo("QUERY");
                assertThat(parameter.path("type").asText()).isEqualTo("int");
                assertThat(parameter.path("required").asBoolean()).isFalse();
                assertThat(parameter.path("defaultValue").asText()).isEqualTo("20");
            });
            assertThat(api.path("parameters")).anySatisfy(parameter -> {
                assertThat(parameter.path("name").asText()).isEqualTo("X-Trace-Id");
                assertThat(parameter.path("source").asText()).isEqualTo("HEADER");
                assertThat(parameter.path("type").asText()).isEqualTo("String");
                assertThat(parameter.path("required").asBoolean()).isFalse();
            });
        });
        assertThat(controllerApis).anySatisfy(api -> {
            assertThat(api.path("controllerName").asText()).isEqualTo("AuditController");
            assertThat(api.path("methodName").asText()).isEqualTo("createAudit");
            assertThat(api.path("httpMethod").asText()).isEqualTo("POST");
            assertThat(api.path("path").asText()).isEqualTo("/api/audit");
            assertThat(api.path("requestType").asText()).isEqualTo("AuditRequest");
            assertThat(api.path("serviceCalls")).isEmpty();
            assertThat(api.path("parameters")).hasSize(1);
            assertThat(api.path("riskScore").asInt()).isEqualTo(80);
            assertThat(api.path("riskLevel").asText()).isEqualTo("HIGH");
            assertThat(api.path("riskHints")).hasSize(3);
            assertThat(api.path("riskHints")).anySatisfy(riskHint -> {
                assertThat(riskHint.path("severity").asText()).isEqualTo("LOW");
                assertThat(riskHint.path("code").asText()).isEqualTo("OPTIONAL_REQUEST_BODY");
                assertThat(riskHint.path("message").asText()).contains("optional");
            });
            assertThat(api.path("riskHints")).anySatisfy(riskHint -> {
                assertThat(riskHint.path("severity").asText()).isEqualTo("MEDIUM");
                assertThat(riskHint.path("code").asText()).isEqualTo("BODY_WITHOUT_VALIDATION");
                assertThat(riskHint.path("message").asText()).contains("@Valid");
            });
            assertThat(api.path("riskHints")).anySatisfy(riskHint -> {
                assertThat(riskHint.path("severity").asText()).isEqualTo("MEDIUM");
                assertThat(riskHint.path("code").asText()).isEqualTo("BODY_TYPE_WITHOUT_CONSTRAINTS");
                assertThat(riskHint.path("message").asText()).contains("AuditRequest", "action", "actor");
                assertThat(riskHint.path("details")).extracting(JsonNode::asText)
                        .containsExactly("action", "actor");
            });
            JsonNode parameter = api.path("parameters").get(0);
            assertThat(parameter.path("name").asText()).isEqualTo("request");
            assertThat(parameter.path("source").asText()).isEqualTo("BODY");
            assertThat(parameter.path("type").asText()).isEqualTo("AuditRequest");
            assertThat(parameter.path("required").asBoolean()).isFalse();
        });
        assertThat(controllerApis).anySatisfy(api -> {
            assertThat(api.path("controllerName").asText()).isEqualTo("AuditController");
            assertThat(api.path("methodName").asText()).isEqualTo("listAuditEvents");
            assertThat(api.path("httpMethod").asText()).isEqualTo("GET");
            assertThat(api.path("path").asText()).isEqualTo("/api/audit/events");
            assertThat(api.path("riskScore").asInt()).isEqualTo(10);
            assertThat(api.path("riskLevel").asText()).isEqualTo("LOW");
            assertThat(api.path("riskHints")).hasSize(1);
            JsonNode riskHint = api.path("riskHints").get(0);
            assertThat(riskHint.path("severity").asText()).isEqualTo("LOW");
            assertThat(riskHint.path("code").asText()).isEqualTo("QUERY_PARAMETER_WITHOUT_BOUNDS");
            assertThat(riskHint.path("message").asText()).contains("page", "upper");
            assertThat(riskHint.path("details")).extracting(JsonNode::asText)
                    .containsExactly("page: missing upper bound");
        });
        assertThat(controllerApis).anySatisfy(api -> {
            assertThat(api.path("controllerName").asText()).isEqualTo("AuditController");
            assertThat(api.path("methodName").asText()).isEqualTo("createPartialAudit");
            assertThat(api.path("httpMethod").asText()).isEqualTo("POST");
            assertThat(api.path("path").asText()).isEqualTo("/api/audit/partial");
            assertThat(api.path("requestType").asText()).isEqualTo("PartialAuditRequest");
            assertThat(api.path("riskScore").asInt()).isEqualTo(45);
            assertThat(api.path("riskLevel").asText()).isEqualTo("MEDIUM");
            assertThat(api.path("riskHints")).hasSize(2);
            assertThat(api.path("riskHints")).anySatisfy(riskHint -> {
                assertThat(riskHint.path("severity").asText()).isEqualTo("MEDIUM");
                assertThat(riskHint.path("code").asText()).isEqualTo("BODY_WITHOUT_VALIDATION");
                assertThat(riskHint.path("details")).isEmpty();
            });
            assertThat(api.path("riskHints")).anySatisfy(riskHint -> {
                assertThat(riskHint.path("severity").asText()).isEqualTo("LOW");
                assertThat(riskHint.path("code").asText()).isEqualTo("BODY_FIELDS_WITHOUT_CONSTRAINTS");
                assertThat(riskHint.path("message").asText()).contains("PartialAuditRequest", "comment");
                assertThat(riskHint.path("details")).extracting(JsonNode::asText)
                        .containsExactly("comment");
            });
        });
        assertThat(controllerApis).anySatisfy(api -> {
            assertThat(api.path("controllerName").asText()).isEqualTo("UserController");
            assertThat(api.path("qualifiedControllerName").asText()).isEqualTo("com.example.demo.user.UserController");
            assertThat(api.path("methodName").asText()).isEqualTo("listUsers");
            assertThat(api.path("httpMethod").asText()).isEqualTo("GET");
            assertThat(api.path("path").asText()).isEqualTo("/api/users");
            assertThat(api.path("responseType").asText()).isEqualTo("List<UserEntity>");
            assertThat(api.path("securityAnnotations")).isEmpty();
            assertThat(api.path("riskScore").asInt()).isEqualTo(35);
            assertThat(api.path("riskLevel").asText()).isEqualTo("MEDIUM");
            assertThat(api.path("riskHints")).hasSize(1);
            JsonNode riskHint = api.path("riskHints").get(0);
            assertThat(riskHint.path("severity").asText()).isEqualTo("MEDIUM");
            assertThat(riskHint.path("code").asText()).isEqualTo("NO_SECURITY_ANNOTATION");
            assertThat(api.path("parameters")).isEmpty();
            assertThat(api.path("serviceCalls")).hasSize(1);
            JsonNode serviceCall = api.path("serviceCalls").get(0);
            assertThat(serviceCall.path("receiverName").asText()).isEqualTo("userService");
            assertThat(serviceCall.path("serviceType").asText()).isEqualTo("UserService");
            assertThat(serviceCall.path("methodName").asText()).isEqualTo("listUsers");
            assertThat(serviceCall.path("line").asInt()).isGreaterThan(0);
            assertThat(serviceCall.path("downstreamCalls")).hasSize(1);
            JsonNode downstreamCall = serviceCall.path("downstreamCalls").get(0);
            assertThat(downstreamCall.path("receiverName").asText()).isEqualTo("userMapper");
            assertThat(downstreamCall.path("componentType").asText()).isEqualTo("UserMapper");
            assertThat(downstreamCall.path("methodName").asText()).isEqualTo("findAll");
            assertThat(downstreamCall.path("line").asInt()).isGreaterThan(0);
            assertThat(api.path("filePath").asText()).isEqualTo("src/main/java/com/example/demo/user/UserController.java");
            assertThat(api.path("startLine").asInt()).isGreaterThan(0);
        });
        assertThat(controllerApis).anySatisfy(api -> {
            assertThat(api.path("controllerName").asText()).isEqualTo("UserController");
            assertThat(api.path("methodName").asText()).isEqualTo("getUser");
            assertThat(api.path("httpMethod").asText()).isEqualTo("GET");
            assertThat(api.path("path").asText()).isEqualTo("/api/users/{id}");
            assertThat(api.path("responseType").asText()).isEqualTo("UserEntity");
            assertThat(api.path("securityAnnotations")).isEmpty();
            assertThat(api.path("riskScore").asInt()).isEqualTo(35);
            assertThat(api.path("riskLevel").asText()).isEqualTo("MEDIUM");
            assertThat(api.path("riskHints")).hasSize(1);
            JsonNode riskHint = api.path("riskHints").get(0);
            assertThat(riskHint.path("severity").asText()).isEqualTo("MEDIUM");
            assertThat(riskHint.path("code").asText()).isEqualTo("NO_SECURITY_ANNOTATION");
            assertThat(api.path("parameters")).hasSize(1);
            JsonNode parameter = api.path("parameters").get(0);
            assertThat(parameter.path("name").asText()).isEqualTo("id");
            assertThat(parameter.path("source").asText()).isEqualTo("PATH");
            assertThat(parameter.path("type").asText()).isEqualTo("Long");
            assertThat(parameter.path("required").asBoolean()).isTrue();
            assertThat(api.path("serviceCalls")).hasSize(1);
            JsonNode serviceCall = api.path("serviceCalls").get(0);
            assertThat(serviceCall.path("receiverName").asText()).isEqualTo("userService");
            assertThat(serviceCall.path("serviceType").asText()).isEqualTo("UserService");
            assertThat(serviceCall.path("methodName").asText()).isEqualTo("getUser");
            assertThat(serviceCall.path("line").asInt()).isGreaterThan(0);
            assertThat(serviceCall.path("downstreamCalls")).hasSize(1);
            JsonNode downstreamCall = serviceCall.path("downstreamCalls").get(0);
            assertThat(downstreamCall.path("receiverName").asText()).isEqualTo("userMapper");
            assertThat(downstreamCall.path("componentType").asText()).isEqualTo("UserMapper");
            assertThat(downstreamCall.path("methodName").asText()).isEqualTo("findById");
            assertThat(downstreamCall.path("line").asInt()).isGreaterThan(0);
            assertThat(api.path("filePath").asText()).isEqualTo("src/main/java/com/example/demo/user/UserController.java");
            assertThat(api.path("startLine").asInt()).isGreaterThan(0);
        });

        JsonNode search = data(mockMvc.perform(get("/api/projects/{id}/search", projectId)
                        .param("query", "UserService")
                        .param("limit", "5")
                        .header(AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.query").value("UserService"))
                .andExpect(jsonPath("$.data.limit").value(5))
                .andReturn());
        assertThat(search.path("results")).anySatisfy(result -> {
            assertThat(result.path("qualifiedName").asText()).isEqualTo("com.example.demo.user.UserService");
            assertThat(result.path("preview").asText()).contains("class UserService");
            assertThat(result.path("filePath").asText()).isEqualTo("src/main/java/com/example/demo/user/UserService.java");
        });
    }

    private String register() throws Exception {
        return register(email, "Project IT");
    }

    private String register(String userEmail, String displayName) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(APPLICATION_JSON)
                        .content(json(Map.of(
                                "email", userEmail,
                                "password", "password123",
                                "displayName", displayName
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn();
        createdEmails.add(userEmail);
        return data(result).path("token").asText();
    }

    private Project project(User owner, String fullName, String repoUrl, ProjectStatus status) {
        Project project = new Project(owner, repoUrl, fullName, "main");
        project.setStatus(status);
        project.setLocalPath(TEST_WORKSPACE.resolve("seed-" + UUID.randomUUID()).toString());
        return projectRepository.save(project);
    }

    private long createProject(String token) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/projects")
                        .header(AUTHORIZATION, bearer(token))
                        .contentType(APPLICATION_JSON)
                        .content(json(Map.of(
                                "repoUrl", demoRepoUrl(),
                                "defaultBranch", "main"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("CREATED"))
                .andReturn();
        return data(result).path("id").asLong();
    }

    private void writeParameterController(Path localPath) throws IOException {
        Path controllerPath = localPath.resolve("src/main/java/com/example/demo/audit/AuditController.java");
        Files.createDirectories(controllerPath.getParent());
        Files.writeString(controllerPath, """
                package com.example.demo.audit;

                import org.springframework.security.access.prepost.PreAuthorize;
                import org.springframework.security.access.annotation.Secured;
                import jakarta.validation.constraints.Min;
                import jakarta.validation.constraints.NotBlank;
                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.PathVariable;
                import org.springframework.web.bind.annotation.PostMapping;
                import org.springframework.web.bind.annotation.RequestBody;
                import org.springframework.web.bind.annotation.RequestHeader;
                import org.springframework.web.bind.annotation.RequestMapping;
                import org.springframework.web.bind.annotation.RequestParam;
                import org.springframework.web.bind.annotation.RestController;

                @RestController
                @RequestMapping("/api/audit")
                @PreAuthorize("hasRole('ADMIN')")
                public class AuditController {

                    @GetMapping("/{id}")
                    @Secured("ROLE_AUDIT")
                    public String readAudit(
                            @PathVariable("id") Long id,
                            @RequestParam(defaultValue = "20") int size,
                            @RequestHeader(name = "X-Trace-Id", required = false) String traceId
                    ) {
                        return "ok";
                    }

                    @GetMapping("/events")
                    public String listAuditEvents(@RequestParam @Min(0) int page) {
                        return "ok";
                    }

                    @PostMapping
                    public String createAudit(@RequestBody(required = false) AuditRequest request) {
                        return "ok";
                    }

                    @PostMapping("/partial")
                    public String createPartialAudit(@RequestBody PartialAuditRequest request) {
                        return "ok";
                    }

                    static class AuditRequest {
                        String action;
                        String actor;
                    }

                    static class PartialAuditRequest {
                        @NotBlank
                        String action;
                        String comment;
                    }
                }
                """);
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

    private String demoRepoUrl() {
        return Path.of("..", "examples", "demo-spring-repo")
                .toAbsolutePath()
                .normalize()
                .toUri()
                .toString();
    }

    private void cleanupUser(String createdEmail) {
        userRepository.findByEmail(createdEmail).ifPresent(user -> {
            for (Project project : projectRepository.findByOwnerIdOrderByCreatedAtDesc(user.getId())) {
                projectRepository.delete(project);
            }
            userRepository.delete(user);
        });
    }

    private void cleanWorkspace() throws IOException {
        if (Files.exists(TEST_WORKSPACE)) {
            try (var stream = Files.walk(TEST_WORKSPACE)) {
                stream.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException exception) {
                        throw new IllegalStateException(exception);
                    }
                });
            } catch (IllegalStateException exception) {
                throw new IOException(exception);
            }
        }
        Files.createDirectories(TEST_WORKSPACE);
    }
}
