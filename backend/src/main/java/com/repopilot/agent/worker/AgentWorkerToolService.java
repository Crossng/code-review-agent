package com.repopilot.agent.worker;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.repopilot.agent.domain.AgentRun;
import com.repopilot.agent.domain.AgentTask;
import com.repopilot.agent.repository.AgentRunRepository;
import com.repopilot.common.ApiException;
import com.repopilot.indexer.dto.CodeSearchResponse;
import com.repopilot.project.dto.ProjectFileResponse;
import com.repopilot.project.dto.ProjectSymbolResponse;
import com.repopilot.project.service.ProjectService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AgentWorkerToolService {

    private static final long MAX_READ_BYTES = 200L * 1024L;

    private final AgentWorkerTokenGuard tokenGuard;
    private final AgentRunRepository agentRunRepository;
    private final ProjectService projectService;

    public AgentWorkerToolService(
            AgentWorkerTokenGuard tokenGuard,
            AgentRunRepository agentRunRepository,
            ProjectService projectService
    ) {
        this.tokenGuard = tokenGuard;
        this.agentRunRepository = agentRunRepository;
        this.projectService = projectService;
    }

    @Transactional(readOnly = true)
    public AgentWorkerRunContextResponse loadRunContext(Long runId, String callbackToken) {
        tokenGuard.requireValidToken(callbackToken);
        return AgentWorkerRunContextResponse.from(requireRun(runId));
    }

    @Transactional(readOnly = true)
    public List<ProjectFileResponse> listProjectFiles(Long runId, String callbackToken, int maxDepth) {
        tokenGuard.requireValidToken(callbackToken);
        AgentTask task = requireRun(runId).getAgentTask();
        return projectService.listFiles(task.getProject().getId(), task.getProject().getOwner().getId(), maxDepth);
    }

    @Transactional(readOnly = true)
    public AgentWorkerFileContentResponse readProjectFile(Long runId, String callbackToken, String relativePath) {
        tokenGuard.requireValidToken(callbackToken);
        AgentTask task = requireRun(runId).getAgentTask();
        Path projectRoot = projectRoot(task);
        Path resolvedPath = resolveSafePath(projectRoot, relativePath);
        try {
            if (!Files.isRegularFile(resolvedPath)) {
                throw new ApiException(HttpStatus.NOT_FOUND, "AGENT_WORKER_FILE_NOT_FOUND", "Project file not found");
            }
            long size = Files.size(resolvedPath);
            if (size > MAX_READ_BYTES) {
                throw new ApiException(
                        HttpStatus.PAYLOAD_TOO_LARGE,
                        "AGENT_WORKER_FILE_TOO_LARGE",
                        "Project file is too large for Agent Worker read_file"
                );
            }
            return new AgentWorkerFileContentResponse(
                    normalizedRelativePath(relativePath),
                    Files.readString(resolvedPath, StandardCharsets.UTF_8),
                    size
            );
        } catch (IOException exception) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "AGENT_WORKER_FILE_READ_FAILED", exception.getMessage());
        }
    }

    @Transactional(readOnly = true)
    public CodeSearchResponse searchCode(Long runId, String callbackToken, String query, int limit) {
        tokenGuard.requireValidToken(callbackToken);
        AgentTask task = requireRun(runId).getAgentTask();
        return projectService.searchCode(task.getProject().getId(), task.getProject().getOwner().getId(), query, limit);
    }

    @Transactional(readOnly = true)
    public List<ProjectSymbolResponse> listSymbols(Long runId, String callbackToken, String type) {
        tokenGuard.requireValidToken(callbackToken);
        AgentTask task = requireRun(runId).getAgentTask();
        return projectService.listSymbols(task.getProject().getId(), task.getProject().getOwner().getId(), type)
                .stream()
                .map(ProjectSymbolResponse::from)
                .toList();
    }

    private AgentRun requireRun(Long runId) {
        return agentRunRepository.findById(runId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "AGENT_RUN_NOT_FOUND", "Agent run not found"));
    }

    private Path projectRoot(AgentTask task) {
        String localPath = task.getProject().getLocalPath();
        if (localPath == null || localPath.isBlank()) {
            throw new ApiException(HttpStatus.CONFLICT, "PROJECT_WORKSPACE_NOT_READY", "Project workspace path is not initialized");
        }
        return Path.of(localPath).toAbsolutePath().normalize();
    }

    private Path resolveSafePath(Path projectRoot, String relativePath) {
        String normalizedRelativePath = normalizedRelativePath(relativePath);
        Path unsafePath = Path.of(normalizedRelativePath);
        if (unsafePath.isAbsolute()
                || normalizedRelativePath.equals("..")
                || normalizedRelativePath.startsWith("../")
                || normalizedRelativePath.contains("/../")
                || normalizedRelativePath.equals(".git")
                || normalizedRelativePath.startsWith(".git/")) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "AGENT_WORKER_FILE_PATH_INVALID",
                    "Agent Worker read_file path must stay inside project workspace"
            );
        }
        Path resolved = projectRoot.resolve(unsafePath).normalize();
        if (!resolved.startsWith(projectRoot)) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "AGENT_WORKER_FILE_PATH_INVALID",
                    "Agent Worker read_file path must stay inside project workspace"
            );
        }
        return resolved;
    }

    private String normalizedRelativePath(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "AGENT_WORKER_FILE_PATH_INVALID",
                    "Agent Worker read_file path is required"
            );
        }
        return relativePath.trim().replace('\\', '/');
    }
}
