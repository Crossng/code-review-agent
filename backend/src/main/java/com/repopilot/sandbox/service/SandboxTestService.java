package com.repopilot.sandbox.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

import com.repopilot.agent.domain.AgentRun;
import com.repopilot.common.ApiException;
import com.repopilot.patch.domain.PatchRecord;
import com.repopilot.project.domain.Project;
import com.repopilot.sandbox.domain.TestRun;
import com.repopilot.sandbox.domain.TestRunStatus;
import com.repopilot.sandbox.repository.TestRunRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class SandboxTestService {

    private static final int LOG_EXCERPT_LIMIT = 12_000;
    private static final int MAX_DURATION_MS = Integer.MAX_VALUE;

    private final TestRunRepository testRunRepository;
    private final Path workspaceRoot;
    private final Path mavenCachePath;
    private final String dockerImage;
    private final Duration timeout;

    public SandboxTestService(
            TestRunRepository testRunRepository,
            @Value("${repopilot.workspace-root}") String workspaceRoot,
            @Value("${repopilot.sandbox.maven-cache:../workspace/maven-cache}") String mavenCachePath,
            @Value("${repopilot.sandbox.docker-image:maven:3.9-eclipse-temurin-17}") String dockerImage,
            @Value("${repopilot.sandbox.timeout-seconds:600}") long timeoutSeconds
    ) {
        this.testRunRepository = testRunRepository;
        this.workspaceRoot = Path.of(workspaceRoot).toAbsolutePath().normalize();
        this.mavenCachePath = Path.of(mavenCachePath).toAbsolutePath().normalize();
        this.dockerImage = dockerImage;
        this.timeout = Duration.ofSeconds(timeoutSeconds);
    }

    public SandboxWorkspace prepareWorkspace(AgentRun run, PatchRecord patch) {
        Project project = run.getAgentTask().getProject();
        if (project.getLocalPath() == null || project.getLocalPath().isBlank()) {
            throw new ApiException(HttpStatus.CONFLICT, "PROJECT_WORKSPACE_NOT_READY", "Project workspace path is not initialized");
        }
        Path projectSource = Path.of(project.getLocalPath()).toAbsolutePath().normalize();
        if (!Files.isDirectory(projectSource)) {
            throw new ApiException(HttpStatus.CONFLICT, "PROJECT_WORKSPACE_NOT_READY", "Project workspace path does not exist");
        }

        Path runRoot = workspaceRoot.resolve("runs").resolve(String.valueOf(run.getId())).normalize();
        ensureRunRoot(runRoot);
        Path sourcePath = runRoot.resolve("source");
        Path patchPath = runRoot.resolve("patch.diff");
        try {
            cleanDirectory(runRoot);
            Files.createDirectories(sourcePath);
            copyDirectory(projectSource, sourcePath);
            Files.writeString(patchPath, patch.getDiffContent(), StandardCharsets.UTF_8);
            return new SandboxWorkspace(runRoot, sourcePath, patchPath);
        } catch (IOException exception) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "SANDBOX_PREPARE_FAILED", exception.getMessage());
        }
    }

    public CommandResult applyPatch(SandboxWorkspace workspace) {
        return runDockerCommand(
                workspace,
                "git apply ../patch.diff",
                workspace.runRoot().resolve("patch-apply.log"),
                false
        );
    }

    public TestRun runMavenTest(AgentRun run, PatchRecord patch, SandboxWorkspace workspace) {
        CommandResult result = runDockerCommand(
                workspace,
                "mvn -q test",
                workspace.runRoot().resolve("test.log"),
                true
        );
        TestRunStatus status = result.success() ? TestRunStatus.PASSED : TestRunStatus.FAILED;
        return testRunRepository.save(new TestRun(
                run,
                patch,
                result.command(),
                result.exitCode(),
                result.durationMs(),
                result.logExcerpt(),
                status
        ));
    }

    private CommandResult runDockerCommand(
            SandboxWorkspace workspace,
            String shellCommand,
            Path logPath,
            boolean mountMavenCache
    ) {
        List<String> command = dockerCommand(workspace, shellCommand, mountMavenCache);
        String commandText = String.join(" ", command);
        long started = System.nanoTime();
        try {
            Files.createDirectories(logPath.getParent());
            ProcessBuilder processBuilder = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(logPath.toFile());
            Process process = processBuilder.start();
            boolean finished = process.waitFor(timeout.toSeconds(), TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return result(commandText, -1, true, started, logPath);
            }
            return result(commandText, process.exitValue(), false, started, logPath);
        } catch (IOException exception) {
            writeFailureLog(logPath, exception);
            return result(commandText, -1, false, started, logPath);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            writeFailureLog(logPath, exception);
            return result(commandText, -1, false, started, logPath);
        }
    }

    private List<String> dockerCommand(SandboxWorkspace workspace, String shellCommand, boolean mountMavenCache) {
        List<String> command = new ArrayList<>();
        command.add("docker");
        command.add("run");
        command.add("--rm");
        command.add("-v");
        command.add(workspace.runRoot().toString() + ":/workspace");
        if (mountMavenCache) {
            try {
                Files.createDirectories(mavenCachePath);
            } catch (IOException exception) {
                throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "SANDBOX_PREPARE_FAILED", exception.getMessage());
            }
            command.add("-v");
            command.add(mavenCachePath.toString() + ":/root/.m2/repository");
        }
        command.add("-w");
        command.add("/workspace/source");
        command.add(dockerImage);
        command.add("sh");
        command.add("-lc");
        command.add(shellCommand);
        return command;
    }

    private CommandResult result(String command, int exitCode, boolean timedOut, long started, Path logPath) {
        long duration = Duration.ofNanos(System.nanoTime() - started).toMillis();
        String excerpt = logExcerpt(logPath);
        if (timedOut) {
            excerpt = appendLine(excerpt, "Command timed out after " + timeout.toSeconds() + " seconds.");
        }
        return new CommandResult(
                command,
                exitCode,
                timedOut,
                (int) Math.min(duration, MAX_DURATION_MS),
                excerpt,
                logPath.toString()
        );
    }

    private void copyDirectory(Path source, Path target) throws IOException {
        try (var stream = Files.walk(source)) {
            for (Path path : stream.sorted().toList()) {
                Path relative = source.relativize(path);
                Path targetPath = target.resolve(relative.toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(targetPath);
                } else {
                    Files.copy(path, targetPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        }
    }

    private void cleanDirectory(Path target) throws IOException {
        if (!Files.exists(target)) {
            return;
        }
        ensureRunRoot(target);
        try (var stream = Files.walk(target)) {
            for (Path path : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private void ensureRunRoot(Path runRoot) {
        Path runsRoot = workspaceRoot.resolve("runs").normalize();
        if (!runRoot.startsWith(runsRoot)) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "SANDBOX_INVALID_PATH", "Run workspace is outside sandbox root");
        }
    }

    private String logExcerpt(Path logPath) {
        if (!Files.exists(logPath)) {
            return "";
        }
        try {
            String content = Files.readString(logPath, StandardCharsets.UTF_8);
            if (content.length() <= LOG_EXCERPT_LIMIT) {
                return content;
            }
            return content.substring(content.length() - LOG_EXCERPT_LIMIT);
        } catch (IOException exception) {
            return "Failed to read sandbox log: " + exception.getMessage();
        }
    }

    private void writeFailureLog(Path logPath, Exception exception) {
        try {
            Files.createDirectories(logPath.getParent());
            Files.writeString(logPath, exception.getMessage(), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
        }
    }

    private String appendLine(String value, String line) {
        if (value == null || value.isBlank()) {
            return line;
        }
        return value + System.lineSeparator() + line;
    }

    public record SandboxWorkspace(Path runRoot, Path sourcePath, Path patchPath) {
    }

    public record CommandResult(
            String command,
            Integer exitCode,
            boolean timedOut,
            Integer durationMs,
            String logExcerpt,
            String logPath
    ) {

        public boolean success() {
            return exitCode != null && exitCode == 0 && !timedOut;
        }
    }
}
