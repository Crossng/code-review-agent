package com.repopilot.pullrequest.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.repopilot.agent.domain.AgentTask;
import com.repopilot.common.ApiException;
import com.repopilot.patch.domain.PatchRecord;
import com.repopilot.project.domain.Project;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class PullRequestGitService {

    private static final Duration GIT_TIMEOUT = Duration.ofMinutes(5);

    private final Path workspaceRoot;

    public PullRequestGitService(@Value("${repopilot.workspace-root}") String workspaceRoot) {
        this.workspaceRoot = Path.of(workspaceRoot).toAbsolutePath().normalize();
    }

    public MaterializedPullRequest materialize(AgentTask task, PatchRecord patch, String title) {
        Project project = task.getProject();
        Path repositoryPath = repositoryPath(project);
        ensureClean(repositoryPath);
        String baseBranch = patch.getBaseBranch();
        String targetBranch = patch.getTargetBranch();
        String commitMessage = commitMessage(task, patch, title);
        Path patchPath = writePatch(task, patch);

        runRequired(repositoryPath, "PULL_REQUEST_GIT_FAILED", List.of("git", "checkout", baseBranch));
        ensureBranchDoesNotExist(repositoryPath, targetBranch);
        runRequired(repositoryPath, "PULL_REQUEST_GIT_FAILED", List.of("git", "checkout", "-b", targetBranch, baseBranch));
        runRequired(repositoryPath, "PULL_REQUEST_PATCH_APPLY_FAILED", List.of("git", "apply", patchPath.toString()));
        runRequired(repositoryPath, "PULL_REQUEST_GIT_FAILED", List.of("git", "add", "-A"));
        GitCommandResult diff = runGit(repositoryPath, List.of("git", "diff", "--cached", "--quiet"));
        if (diff.success()) {
            throw new ApiException(HttpStatus.CONFLICT, "PULL_REQUEST_NO_CHANGES", "Patch did not produce committed changes");
        }
        runRequired(repositoryPath, "PULL_REQUEST_GIT_FAILED", List.of(
                "git",
                "-c",
                "user.name=RepoPilot",
                "-c",
                "user.email=repopilot@example.local",
                "commit",
                "-m",
                commitMessage
        ));
        String commitSha = runRequired(repositoryPath, "PULL_REQUEST_GIT_FAILED", List.of("git", "rev-parse", "HEAD")).output().trim();
        ensureClean(repositoryPath);
        runRequired(repositoryPath, "PULL_REQUEST_GIT_FAILED", List.of("git", "checkout", baseBranch));
        ensureClean(repositoryPath);
        return new MaterializedPullRequest(baseBranch, targetBranch, commitSha, commitMessage);
    }

    public void pushBranch(Project project, String targetBranch, String token) {
        Path repositoryPath = repositoryPath(project);
        ensureClean(repositoryPath);
        runRequired(repositoryPath, "PULL_REQUEST_GIT_FAILED", List.of("git", "checkout", targetBranch));
        runRequired(
                repositoryPath,
                "GITHUB_BRANCH_PUSH_FAILED",
                List.of("git", "push", "origin", targetBranch),
                gitAuthEnvironment(token)
        );
        ensureClean(repositoryPath);
    }

    private Path repositoryPath(Project project) {
        if (project.getLocalPath() == null || project.getLocalPath().isBlank()) {
            throw new ApiException(HttpStatus.CONFLICT, "PROJECT_WORKSPACE_NOT_READY", "Project workspace path is not initialized");
        }
        Path repositoryPath = Path.of(project.getLocalPath()).toAbsolutePath().normalize();
        Path reposRoot = workspaceRoot.resolve("repos").normalize();
        if (!repositoryPath.startsWith(reposRoot)) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "PULL_REQUEST_INVALID_PATH", "Repository workspace is outside RepoPilot root");
        }
        if (!Files.isDirectory(repositoryPath)) {
            throw new ApiException(HttpStatus.CONFLICT, "PROJECT_WORKSPACE_NOT_READY", "Project workspace path does not exist");
        }
        runRequired(repositoryPath, "PULL_REQUEST_GIT_FAILED", List.of("git", "rev-parse", "--is-inside-work-tree"));
        return repositoryPath;
    }

    private void ensureClean(Path repositoryPath) {
        GitCommandResult status = runRequired(repositoryPath, "PULL_REQUEST_WORKTREE_CHECK_FAILED", List.of("git", "status", "--porcelain"));
        if (!status.output().isBlank()) {
            throw new ApiException(HttpStatus.CONFLICT, "PULL_REQUEST_WORKTREE_DIRTY", "Repository workspace has uncommitted changes");
        }
    }

    private void ensureBranchDoesNotExist(Path repositoryPath, String targetBranch) {
        GitCommandResult result = runGit(repositoryPath, List.of("git", "show-ref", "--verify", "--quiet", "refs/heads/" + targetBranch));
        if (result.success()) {
            throw new ApiException(HttpStatus.CONFLICT, "PULL_REQUEST_BRANCH_EXISTS", "Target branch already exists in local workspace");
        }
    }

    private Path writePatch(AgentTask task, PatchRecord patch) {
        Path pullRequestRoot = workspaceRoot.resolve("pull-requests").resolve(String.valueOf(task.getId())).normalize();
        Path allowedRoot = workspaceRoot.resolve("pull-requests").normalize();
        if (!pullRequestRoot.startsWith(allowedRoot)) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "PULL_REQUEST_INVALID_PATH", "Pull request workspace is outside RepoPilot root");
        }
        try {
            cleanDirectory(pullRequestRoot);
            Files.createDirectories(pullRequestRoot);
            Path patchPath = pullRequestRoot.resolve("patch.diff");
            Files.writeString(patchPath, patch.getDiffContent(), StandardCharsets.UTF_8);
            return patchPath;
        } catch (IOException exception) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "PULL_REQUEST_PATCH_WRITE_FAILED", exception.getMessage());
        }
    }

    private void cleanDirectory(Path target) throws IOException {
        if (!Files.exists(target)) {
            return;
        }
        try (var stream = Files.walk(target)) {
            for (Path path : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private GitCommandResult runRequired(Path workingDirectory, String errorCode, List<String> command) {
        return runRequired(workingDirectory, errorCode, command, Map.of());
    }

    private GitCommandResult runRequired(Path workingDirectory, String errorCode, List<String> command, Map<String, String> environment) {
        GitCommandResult result = runGit(workingDirectory, command, environment);
        if (!result.success()) {
            throw new ApiException(HttpStatus.CONFLICT, errorCode, result.output());
        }
        return result;
    }

    private GitCommandResult runGit(Path workingDirectory, List<String> command) {
        return runGit(workingDirectory, command, Map.of());
    }

    private GitCommandResult runGit(Path workingDirectory, List<String> command, Map<String, String> environment) {
        try {
            ProcessBuilder processBuilder = new ProcessBuilder(command)
                    .directory(workingDirectory.toFile())
                    .redirectErrorStream(true);
            processBuilder.environment().putAll(environment);
            Process process = processBuilder.start();
            boolean finished = process.waitFor(GIT_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new GitCommandResult(false, "Git command timed out");
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return new GitCommandResult(process.exitValue() == 0, output);
        } catch (IOException exception) {
            return new GitCommandResult(false, exception.getMessage());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return new GitCommandResult(false, "Git command interrupted");
        }
    }

    private String commitMessage(AgentTask task, PatchRecord patch, String title) {
        return title + "\n\n"
                + "由 RepoPilot 生成。\n\n"
                + "任务：#" + task.getId() + "\n"
                + "运行：#" + patch.getAgentRun().getId() + "\n"
                + "补丁：#" + patch.getId() + "\n"
                + "测试：mvn test 已通过";
    }

    private Map<String, String> gitAuthEnvironment(String token) {
        if (token == null || token.isBlank()) {
            return Map.of();
        }
        Map<String, String> environment = new HashMap<>();
        environment.put("GIT_CONFIG_COUNT", "1");
        environment.put("GIT_CONFIG_KEY_0", "http.extraHeader");
        environment.put("GIT_CONFIG_VALUE_0", "Authorization: Bearer " + token);
        return environment;
    }

    private record GitCommandResult(boolean success, String output) {
    }

    public record MaterializedPullRequest(
            String baseBranch,
            String targetBranch,
            String commitSha,
            String commitMessage
    ) {
    }
}
