package com.repopilot.repository.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

import com.repopilot.common.ApiException;
import com.repopilot.project.domain.Project;
import com.repopilot.project.domain.ProjectStatus;
import com.repopilot.project.repository.ProjectRepository;
import com.repopilot.repository.dto.CloneProjectResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GitRepositoryService {

    private static final Duration GIT_TIMEOUT = Duration.ofMinutes(5);

    private final ProjectRepository projectRepository;

    public GitRepositoryService(ProjectRepository projectRepository) {
        this.projectRepository = projectRepository;
    }

    @Transactional
    public CloneProjectResponse cloneProject(Project project) {
        project.setStatus(ProjectStatus.CLONING);
        projectRepository.save(project);

        Path target = projectPath(project);
        cleanDirectory(target);

        try {
            Files.createDirectories(target.getParent());
            GitCommandResult cloneResult = runGit(target.getParent(), List.of(
                    "git",
                    "clone",
                    "--depth",
                    "1",
                    "--branch",
                    project.getDefaultBranch(),
                    project.getRepoUrl(),
                    target.getFileName().toString()
            ));
            if (!cloneResult.success()) {
                cleanDirectory(target);
                cloneResult = runGit(target.getParent(), List.of(
                        "git",
                        "clone",
                        "--depth",
                        "1",
                        project.getRepoUrl(),
                        target.getFileName().toString()
                ));
            }
            if (!cloneResult.success()) {
                project.setStatus(ProjectStatus.FAILED);
                projectRepository.save(project);
                throw new ApiException(HttpStatus.BAD_GATEWAY, "PROJECT_CLONE_FAILED", cloneResult.output());
            }

            String commitSha = runGit(target, List.of("git", "rev-parse", "HEAD")).output().trim();
            String branch = runGit(target, List.of("git", "rev-parse", "--abbrev-ref", "HEAD")).output().trim();
            int fileCount = countFiles(target);
            int javaFileCount = countJavaFiles(target);
            project.setLocalPath(target.toString());
            project.setStatus(ProjectStatus.READY);
            projectRepository.save(project);
            return new CloneProjectResponse(
                    project.getId(),
                    project.getStatus(),
                    target.toString(),
                    branch,
                    commitSha,
                    fileCount,
                    javaFileCount,
                    "Repository cloned successfully"
            );
        } catch (IOException exception) {
            project.setStatus(ProjectStatus.FAILED);
            projectRepository.save(project);
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "PROJECT_CLONE_FAILED", exception.getMessage());
        }
    }

    private Path projectPath(Project project) {
        if (project.getLocalPath() == null) {
            throw new ApiException(HttpStatus.CONFLICT, "PROJECT_WORKSPACE_NOT_READY", "Project workspace path is not initialized");
        }
        return Path.of(project.getLocalPath()).toAbsolutePath().normalize();
    }

    private GitCommandResult runGit(Path workingDirectory, List<String> command) {
        try {
            Process process = new ProcessBuilder(command)
                    .directory(workingDirectory.toFile())
                    .redirectErrorStream(true)
                    .start();
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

    private int countFiles(Path root) throws IOException {
        try (var stream = Files.walk(root)) {
            return (int) stream
                    .filter(Files::isRegularFile)
                    .filter(path -> !path.toString().contains("/.git/"))
                    .count();
        }
    }

    private int countJavaFiles(Path root) throws IOException {
        try (var stream = Files.walk(root)) {
            return (int) stream
                    .filter(Files::isRegularFile)
                    .filter(path -> !path.toString().contains("/.git/"))
                    .filter(path -> path.toString().endsWith(".java"))
                    .count();
        }
    }

    private void cleanDirectory(Path target) {
        if (!Files.exists(target)) {
            return;
        }
        try (var stream = Files.walk(target)) {
            stream.sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException exception) {
                            throw new IllegalStateException(exception);
                        }
                    });
        } catch (IOException | IllegalStateException exception) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "PROJECT_WORKSPACE_CLEAN_FAILED", exception.getMessage());
        }
    }

    private record GitCommandResult(boolean success, String output) {
    }
}
