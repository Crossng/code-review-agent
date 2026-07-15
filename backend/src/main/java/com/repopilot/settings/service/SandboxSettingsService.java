package com.repopilot.settings.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import com.repopilot.settings.dto.SandboxSettingsCheckResponse;
import com.repopilot.settings.dto.SandboxSettingsResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class SandboxSettingsService {

    private static final Duration DOCKER_CHECK_TIMEOUT = Duration.ofSeconds(5);

    private final String workspaceRoot;
    private final String mavenCachePath;
    private final String dockerImage;
    private final long timeoutSeconds;
    private final boolean dockerCheckEnabled;

    public SandboxSettingsService(
            @Value("${repopilot.workspace-root}") String workspaceRoot,
            @Value("${repopilot.sandbox.maven-cache:../workspace/maven-cache}") String mavenCachePath,
            @Value("${repopilot.sandbox.docker-image:maven:3.9-eclipse-temurin-17}") String dockerImage,
            @Value("${repopilot.sandbox.timeout-seconds:600}") long timeoutSeconds,
            @Value("${repopilot.sandbox.docker-check-enabled:true}") boolean dockerCheckEnabled
    ) {
        this.workspaceRoot = workspaceRoot;
        this.mavenCachePath = mavenCachePath;
        this.dockerImage = dockerImage;
        this.timeoutSeconds = timeoutSeconds;
        this.dockerCheckEnabled = dockerCheckEnabled;
    }

    public SandboxSettingsResponse current() {
        Path normalizedWorkspaceRoot = normalize(workspaceRoot);
        Path normalizedMavenCache = normalize(mavenCachePath);
        PathStatus workspaceStatus = pathStatus(normalizedWorkspaceRoot);
        PathStatus cacheStatus = pathStatus(normalizedMavenCache);
        DockerStatus dockerStatus = dockerStatus();
        boolean imageConfigured = isPresent(dockerImage);
        boolean timeoutConfigured = timeoutSeconds > 0;

        List<SandboxSettingsCheckResponse> checks = new ArrayList<>();
        checks.add(dockerCheck(dockerStatus));
        checks.add(check(
                "DOCKER_IMAGE",
                "Docker image",
                imageConfigured ? "PASS" : "BLOCKED",
                imageConfigured ? "Sandbox image is configured." : "Sandbox Docker image is missing."
        ));
        checks.add(check(
                "WORKSPACE_ROOT",
                "Workspace root",
                workspaceStatus.writable() ? "PASS" : "BLOCKED",
                workspaceStatus.message("Workspace root")
        ));
        checks.add(check(
                "MAVEN_CACHE",
                "Maven cache",
                cacheStatus.writable() ? "PASS" : "BLOCKED",
                cacheStatus.message("Maven cache")
        ));
        checks.add(check(
                "TIMEOUT",
                "Sandbox timeout",
                timeoutConfigured ? "PASS" : "BLOCKED",
                timeoutConfigured ? "Sandbox timeout is " + timeoutSeconds + " seconds." : "Sandbox timeout must be greater than zero."
        ));

        List<String> missingRequirements = checks.stream()
                .filter(check -> check.status().equals("BLOCKED"))
                .map(SandboxSettingsCheckResponse::code)
                .map(String::toLowerCase)
                .toList();

        return new SandboxSettingsResponse(
                missingRequirements.isEmpty(),
                blankToNull(dockerImage),
                imageConfigured,
                Math.max(0, timeoutSeconds),
                normalizedWorkspaceRoot.toString(),
                workspaceStatus.exists(),
                workspaceStatus.writable(),
                normalizedMavenCache.toString(),
                cacheStatus.exists(),
                cacheStatus.writable(),
                dockerCheckEnabled,
                dockerStatus.available(),
                dockerStatus.version(),
                missingRequirements,
                checks
        );
    }

    private SandboxSettingsCheckResponse dockerCheck(DockerStatus status) {
        if (!dockerCheckEnabled) {
            return check("DOCKER_DAEMON", "Docker daemon", "WARN", "Docker daemon check is disabled.");
        }
        if (status.available()) {
            return check("DOCKER_DAEMON", "Docker daemon", "PASS", "Docker daemon responded with version " + status.version() + ".");
        }
        return check("DOCKER_DAEMON", "Docker daemon", "BLOCKED", status.message());
    }

    private DockerStatus dockerStatus() {
        if (!dockerCheckEnabled) {
            return new DockerStatus(false, null, "Docker daemon check is disabled.");
        }
        List<String> command = List.of("docker", "version", "--format", "{{.Server.Version}}");
        try {
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();
            boolean finished = process.waitFor(DOCKER_CHECK_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new DockerStatus(false, null, "Docker daemon check timed out after " + DOCKER_CHECK_TIMEOUT.toSeconds() + " seconds.");
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (process.exitValue() != 0) {
                return new DockerStatus(false, null, output.isBlank() ? "Docker daemon did not respond." : output);
            }
            return new DockerStatus(true, output.isBlank() ? "unknown" : output, "Docker daemon responded.");
        } catch (IOException exception) {
            return new DockerStatus(false, null, "Docker check failed: " + exception.getMessage());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return new DockerStatus(false, null, "Docker check was interrupted.");
        }
    }

    private PathStatus pathStatus(Path path) {
        boolean exists = Files.exists(path);
        if (exists) {
            return new PathStatus(exists, Files.isDirectory(path) && Files.isWritable(path));
        }
        Path parent = nearestExistingParent(path);
        return new PathStatus(false, parent != null && Files.isDirectory(parent) && Files.isWritable(parent));
    }

    private Path nearestExistingParent(Path path) {
        Path current = path.getParent();
        while (current != null) {
            if (Files.exists(current)) {
                return current;
            }
            current = current.getParent();
        }
        return null;
    }

    private Path normalize(String value) {
        return Path.of(value == null || value.isBlank() ? "." : value).toAbsolutePath().normalize();
    }

    private SandboxSettingsCheckResponse check(String code, String label, String status, String message) {
        return new SandboxSettingsCheckResponse(code, label, status, message);
    }

    private boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }

    private String blankToNull(String value) {
        return isPresent(value) ? value.trim() : null;
    }

    private record DockerStatus(boolean available, String version, String message) {
    }

    private record PathStatus(boolean exists, boolean writable) {

        String message(String label) {
            if (exists && writable) {
                return label + " exists and is writable.";
            }
            if (exists) {
                return label + " exists but is not writable.";
            }
            if (writable) {
                return label + " does not exist yet, but its parent can create it.";
            }
            return label + " does not exist and its parent is not writable.";
        }
    }
}
