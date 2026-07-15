package com.repopilot.pullrequest.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.repopilot.agent.domain.AgentRun;
import com.repopilot.agent.domain.AgentTask;
import com.repopilot.agent.domain.AgentTaskType;
import com.repopilot.patch.domain.PatchRecord;
import com.repopilot.project.domain.Project;
import com.repopilot.pullrequest.domain.PullRequestProvider;
import com.repopilot.pullrequest.domain.PullRequestRecord;
import com.repopilot.pullrequest.domain.PullRequestStatus;
import com.repopilot.user.domain.User;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitHubPullRequestServiceTest {

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
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();
        startGitHubApiStub(authorization, requestBody);

        Path remoteRepository = workspaceRoot.resolve("git-remotes").resolve("demo.git");
        Path repository = workspaceRoot.resolve("repos").resolve("remote-pr-" + UUID.randomUUID()).resolve("source");
        String targetBranch = "repopilot/task-remote";
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
        git(repository, "checkout", "-b", targetBranch, "main");
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
                targetBranch,
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
                targetBranch,
                commitSha,
                "RepoPilot：远端 PR 发布验证",
                PullRequestStatus.DRAFT_READY
        );
        GitHubPullRequestService service = new GitHubPullRequestService(
                new PullRequestGitService(workspaceRoot.toString()),
                objectMapper,
                true,
                serverBaseUrl(),
                "test-token"
        );

        GitHubPullRequestService.GitHubPullRequest pullRequest = service.publish(project, record);

        assertThat(pullRequest.number()).isEqualTo(42);
        assertThat(pullRequest.url()).isEqualTo("https://github.com/example/demo/pull/42");
        assertThat(record.getRemotePushedAt()).isNotNull();
        assertThat(git(remoteRepository, "rev-parse", "refs/heads/" + targetBranch).trim()).isEqualTo(commitSha);
        assertThat(authorization.get()).isEqualTo("Bearer test-token");
        JsonNode body = objectMapper.readTree(requestBody.get());
        assertThat(body.path("title").asText()).isEqualTo("RepoPilot：远端 PR 发布验证");
        assertThat(body.path("head").asText()).isEqualTo(targetBranch);
        assertThat(body.path("base").asText()).isEqualTo("main");
        assertThat(body.path("body").asText()).contains("由 RepoPilot 准备。");
    }

    private void startGitHubApiStub(
            AtomicReference<String> authorization,
            AtomicReference<String> requestBody
    ) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/repos/example/demo/pulls", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = """
                    {
                      "number": 42,
                      "html_url": "https://github.com/example/demo/pull/42"
                    }
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(201, bytes.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        });
        server.start();
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
}
