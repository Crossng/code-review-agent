package com.repopilot.agent.service;

import java.util.Collection;
import java.util.Set;

import com.repopilot.agent.domain.AgentTask;
import com.repopilot.agent.domain.AgentTaskStatus;
import com.repopilot.agent.repository.AgentTaskRepository;
import com.repopilot.common.ApiException;
import com.repopilot.project.repository.ProjectRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class ProjectWriteGuardService {

    private static final Set<AgentTaskStatus> ACTIVE_WRITE_STATUSES = Set.of(
            AgentTaskStatus.REPO_INDEXING,
            AgentTaskStatus.PLANNING,
            AgentTaskStatus.RETRIEVING_CONTEXT,
            AgentTaskStatus.GENERATING_PATCH,
            AgentTaskStatus.APPLYING_PATCH_IN_SANDBOX,
            AgentTaskStatus.RUNNING_TESTS,
            AgentTaskStatus.REPAIRING,
            AgentTaskStatus.REVIEWING_PATCH,
            AgentTaskStatus.CREATING_PULL_REQUEST
    );

    private final ProjectRepository projectRepository;
    private final AgentTaskRepository agentTaskRepository;

    public ProjectWriteGuardService(
            ProjectRepository projectRepository,
            AgentTaskRepository agentTaskRepository
    ) {
        this.projectRepository = projectRepository;
        this.agentTaskRepository = agentTaskRepository;
    }

    public void ensureProjectWriteSlot(AgentTask task) {
        ensureProjectWriteSlot(task, null, null);
    }

    public void ensureProjectWriteSlot(
            AgentTask task,
            Collection<AgentTaskStatus> allowedCurrentStatuses,
            String invalidStatusMessage
    ) {
        Long projectId = task.getProject().getId();
        projectRepository.findByIdForUpdate(projectId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PROJECT_NOT_FOUND", "Project not found"));
        if (allowedCurrentStatuses != null) {
            AgentTaskStatus latestStatus = agentTaskRepository.findStatusById(task.getId())
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "AGENT_TASK_NOT_FOUND", "Agent task not found"));
            if (!allowedCurrentStatuses.contains(latestStatus)) {
                throw new ApiException(HttpStatus.CONFLICT, "AGENT_INVALID_STATUS", invalidStatusMessage);
            }
        }
        boolean occupied = agentTaskRepository.existsByProjectIdAndStatusInAndIdNot(
                projectId,
                ACTIVE_WRITE_STATUSES,
                task.getId()
        );
        if (occupied) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "PROJECT_WRITE_TASK_RUNNING",
                    "Another write task is already running for this project"
            );
        }
    }
}
