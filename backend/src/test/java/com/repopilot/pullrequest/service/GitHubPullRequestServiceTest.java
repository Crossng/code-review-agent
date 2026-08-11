package com.repopilot.pullrequest.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.repopilot.agent.domain.AgentRun;
import com.repopilot.agent.domain.AgentTask;
import com.repopilot.agent.domain.AgentTaskType;
import com.repopilot.common.ApiException;
import com.repopilot.patch.domain.PatchRecord;
import com.repopilot.project.domain.Project;
import com.repopilot.pullrequest.domain.PullRequestProvider;
import com.repopilot.pullrequest.domain.PullRequestPublishOutcome;
import com.repopilot.pullrequest.domain.PullRequestRecord;
import com.repopilot.pullrequest.domain.PullRequestStatus;
import com.repopilot.user.domain.User;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitHubPullRequestServiceTest {

    private static final String TARGET_BRANCH = "repopilot/task-remote";
    private static final String PULL_REQUEST_URL = "https://github.com/example/demo/pull/42";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void publishPushesTargetBranchAndCreatesRemotePullRequest(@TempDir Path workspaceRoot) throws Exception {
        GitHubStub stub = startGitHubApiStub(StubMode.CREATE_SUCCESS);
        PullRequestFixture fixture = fixture(workspaceRoot);

        GitHubPullRequestService.GitHubPullRequest pullRequest = service(workspaceRoot).publish(
                fixture.project(),
                fixture.record()
        );

        assertThat(pullRequest.number()).isEqualTo(42);
        assertThat(pullRequest.url()).isEqualTo(PULL_REQUEST_URL);
        assertThat(pullRequest.publishOutcome()).isEqualTo(PullRequestPublishOutcome.REMOTE_CREATED);
        assertThat(fixture.record().getRemotePushedAt()).isNotNull();
        assertThat(git(fixture.remoteRepository(), "rev-parse", "refs/heads/" + TARGET_BRANCH).trim())
                .isEqualTo(fixture.commitSha());
        assertThat(stub.requests()).hasSize(2);
        assertLookupRequest(stub.requests().get(0));
        assertCreateRequest(stub.requests().get(1), fixture.record());
    }

    @Test
    void publishReusesMatchingOpenPullRequestWithoutSecondCreate(@TempDir Path workspaceRoot) throws Exception {
        GitHubStub stub = startGitHubApiStub(StubMode.EXISTING_BEFORE_CREATE);
        PullRequestFixture fixture = fixture(workspaceRoot);

        GitHubPullRequestService.GitHubPullRequest pullRequest = service(workspaceRoot).publish(
                fixture.project(),
                fixture.record()
        );

        assertThat(pullRequest.number()).isEqualTo(42);
        assertThat(pullRequest.url()).isEqualTo(PULL_REQUEST_URL);
        assertThat(pullRequest.publishOutcome()).isEqualTo(PullRequestPublishOutcome.REMOTE_REUSED_EXISTING);
        assertThat(stub.requests()).hasSize(1);
        assertLookupRequest(stub.requests().get(0));
    }

    @Test
    void publishReconcilesMatchingPullRequestAfterCreateConflict(@TempDir Path workspaceRoot) throws Exception {
        GitHubStub stub = startGitHubApiStub(StubMode.CONFLICT_THEN_EXISTING);
        PullRequestFixture fixture = fixture(workspaceRoot);

        GitHubPullRequestService.GitHubPullRequest pullRequest = service(workspaceRoot).publish(
                fixture.project(),
                fixture.record()
        );

        assertThat(pullRequest.number()).isEqualTo(42);
        assertThat(pullRequest.url()).isEqualTo(PULL_REQUEST_URL);
        assertThat(pullRequest.publishOutcome()).isEqualTo(PullRequestPublishOutcome.REMOTE_RECONCILED);
        assertThat(stub.requests()).hasSize(3);
        assertLookupRequest(stub.requests().get(0));
        assertCreateRequest(stub.requests().get(1), fixture.record());
        assertLookupRequest(stub.requests().get(2));
    }

    @Test
    void publishReconcilesMatchingPullRequestAfterMalformedCreateResponse(@TempDir Path workspaceRoot) throws Exception {
        GitHubStub stub = startGitHubApiStub(StubMode.MALFORMED_RESPONSE_THEN_EXISTING);
        PullRequestFixture fixture = fixture(workspaceRoot);

        GitHubPullRequestService.GitHubPullRequest pullRequest = service(workspaceRoot).publish(
                fixture.project(),
                fixture.record()
        );

        assertThat(pullRequest.number()).isEqualTo(42);
        assertThat(pullRequest.url()).isEqualTo(PULL_REQUEST_URL);
        assertThat(pullRequest.publishOutcome()).isEqualTo(PullRequestPublishOutcome.REMOTE_RECONCILED);
        assertThat(stub.requests()).hasSize(3);
        assertLookupRequest(stub.requests().get(0));
        assertCreateRequest(stub.requests().get(1), fixture.record());
        assertLookupRequest(stub.requests().get(2));
    }

    @Test
    void publishKeepsCreateFailureWhenConflictHasNoMatchingPullRequest(@TempDir Path workspaceRoot) throws Exception {
        GitHubStub stub = startGitHubApiStub(StubMode.CONFLICT_WITHOUT_EXISTING);
        PullRequestFixture fixture = fixture(workspaceRoot);

        assertThatThrownBy(() -> service(workspaceRoot).publish(fixture.project(), fixture.record()))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo("GITHUB_PR_CREATE_FAILED");
                    assertThat(exception.getMessage()).contains("HTTP 422");
                });
        assertThat(stub.requests()).hasSize(3);
        assertLookupRequest(stub.requests().get(0));
        assertCreateRequest(stub.requests().get(1), fixture.record());
        assertLookupRequest(stub.requests().get(2));
    }

    private GitHubPullRequestService service(Path workspaceRoot) {
        return new GitHubPullRequestService(
                new PullRequestGitService(workspaceRoot.toString()),
                objectMapper,
                true,
                serverBaseUrl(),
                "test-token"
        );
    }

    private PullRequestFixture fixture(Path workspaceRoot) throws Exception {
        Path remoteRepository = workspaceRoot.resolve("git-remotes").resolve("demo.git");
        Path repository = workspaceRoot.resolve("repos").resolve("remote-pr-" + UUID.randomUUID()).resolve("source");
        Files.createDirectories(remoteRepository.getParent());
        Files.createDirectories(repository);
        git(workspaceRoot, "init", "--bare", remoteRepository.toString());
        git(repository, "init");
        git(repository, "checkout", "-b", "main");
        Files.writeString(repository.resolve("README.md"), "hello from remote pr\n", StandardCharsets.UTF_8);
        git(repository, "add", "README.md");
        git(repository, "-c", "user.name=RepoPilot Test", "-c", "user.email=repopilot-test@example.local", "commit", "-m", "Initial commit");
        git(repository, "remote", "add", "origin", remoteRepository.toString());
        git(repository, "push", "origin", "main");
        git(repository, "checkout", "-b", TARGET_BRANCH, "main");
        Files.writeString(repository.resolve("README.md"), "hello from remote pr\npublished by RepoPilot\n", StandardCharsets.UTF_8);
        git(repository, "add", "README.md");
        git(repository, "-c", "user.name=RepoPilot Test", "-c", "user.email=repopilot-test@example.local", "commit", "-m", "RepoPilot remote PR");
        String commitSha = git(repository, "rev-parse", "HEAD").trim();
        git(repository, "checkout", "main");

        User user = new User("remote-pr@example.test", "hash", "Remote PR", "USER");
        Project project = new Project(user, "https://github.com/example/demo.git", "example/demo", "main");
        project.setLocalPath(repository.toString());
        AgentTask task = new AgentTask(
                project,
                user,
                AgentTaskType.FEATURE,
                "远端 PR 发布验证",
                "验证 RepoPilot 可以推送分支并调用 GitHub PR API。"
        );
        AgentRun run = new AgentRun(task);
        PatchRecord patch = new PatchRecord(
                task,
                run,
                "main",
                TARGET_BRANCH,
                "diff --git a/README.md b/README.md\n",
                "远端 PR 发布测试补丁"
        );
        PullRequestRecord record = new PullRequestRecord(
                task,
                patch,
                PullRequestProvider.GITHUB,
                "RepoPilot：远端 PR 发布验证",
                "由 RepoPilot 准备。",
                "main",
                TARGET_BRANCH,
                commitSha,
                "RepoPilot：远端 PR 发布验证",
                PullRequestStatus.DRAFT_READY
        );
        return new PullRequestFixture(project, record, remoteRepository, commitSha);
    }

    private GitHubStub startGitHubApiStub(StubMode mode) throws IOException {
        List<RecordedRequest> requests = new ArrayList<>();
        int[] lookupCount = {0};
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/repos/example/demo/pulls", exchange -> {
            String method = exchange.getRequestMethod();
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(new RecordedRequest(
                    method,
                    exchange.getRequestURI().getRawQuery(),
                    exchange.getRequestHeaders().getFirst("Authorization"),
                    exchange.getRequestHeaders().getFirst("Accept"),
                    exchange.getRequestHeaders().getFirst("X-GitHub-Api-Version"),
                    body
            ));

            if ("GET".equals(method)) {
                lookupCount[0]++;
                boolean existing = mode == StubMode.EXISTING_BEFORE_CREATE
                        || ((mode == StubMode.CONFLICT_THEN_EXISTING
                                || mode == StubMode.MALFORMED_RESPONSE_THEN_EXISTING)
                                && lookupCount[0] > 1);
                respond(exchange, 200, existing ? "[" + pullRequestJson() + "]" : "[]");
                return;
            }
            if ("POST".equals(method)) {
                if (mode == StubMode.CREATE_SUCCESS) {
                    respond(exchange, 201, pullRequestJson());
                } else if (mode == StubMode.MALFORMED_RESPONSE_THEN_EXISTING) {
                    respond(exchange, 201, "not-json");
                } else {
                    respond(exchange, 422, "{\"message\":\"A pull request already exists\"}");
                }
                return;
            }
            respond(exchange, 405, "");
        });
        server.start();
        return new GitHubStub(requests);
    }

    private String pullRequestJson() {
        return """
                {
                  "number": 42,
                  "html_url": "https://github.com/example/demo/pull/42",
                  "state": "open",
                  "head": {"ref": "repopilot/task-remote"},
                  "base": {"ref": "main"}
                }
                """;
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private void assertLookupRequest(RecordedRequest request) {
        assertThat(request.method()).isEqualTo("GET");
        assertThat(URLDecoder.decode(request.rawQuery(), StandardCharsets.UTF_8))
                .contains("state=open")
                .contains("head=example:" + TARGET_BRANCH)
                .contains("base=main")
                .contains("per_page=1");
        assertCommonHeaders(request);
    }

    private void assertCreateRequest(RecordedRequest request, PullRequestRecord record) throws Exception {
        assertThat(request.method()).isEqualTo("POST");
        assertCommonHeaders(request);
        JsonNode body = objectMapper.readTree(request.body());
        assertThat(body.path("title").asText()).isEqualTo(record.getTitle());
        assertThat(body.path("head").asText()).isEqualTo(TARGET_BRANCH);
        assertThat(body.path("base").asText()).isEqualTo("main");
        assertThat(body.path("body").asText()).contains("由 RepoPilot 准备");
    }

    private void assertCommonHeaders(RecordedRequest request) {
        assertThat(request.authorization()).isEqualTo("Bearer test-token");
        assertThat(request.accept()).isEqualTo("application/vnd.github+json");
        assertThat(request.apiVersion()).isEqualTo("2022-11-28");
    }

    private String serverBaseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private String git(Path workingDirectory, String... args) throws IOException, InterruptedException {
        ProcessBuilder processBuilder = new ProcessBuilder(command(args))
                .directory(workingDirectory.toFile())
                .redirectErrorStream(true);
        Process process = processBuilder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode = process.waitFor();
        assertThat(exitCode)
                .as(output)
                .isZero();
        return output;
    }

    private String[] command(String... args) {
        String[] command = new String[args.length + 1];
        command[0] = "git";
        System.arraycopy(args, 0, command, 1, args.length);
        return command;
    }

    private enum StubMode {
        CREATE_SUCCESS,
        EXISTING_BEFORE_CREATE,
        CONFLICT_THEN_EXISTING,
        MALFORMED_RESPONSE_THEN_EXISTING,
        CONFLICT_WITHOUT_EXISTING
    }

    private record PullRequestFixture(
            Project project,
            PullRequestRecord record,
            Path remoteRepository,
            String commitSha
    ) {
    }

    private record GitHubStub(List<RecordedRequest> requests) {
    }

    private record RecordedRequest(
            String method,
            String rawQuery,
            String authorization,
            String accept,
            String apiVersion,
            String body
    ) {
    }
}
