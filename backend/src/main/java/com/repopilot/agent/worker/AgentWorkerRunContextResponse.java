package com.repopilot.agent.worker;

import com.repopilot.agent.domain.AgentRun;
import com.repopilot.agent.domain.AgentRunStatus;
import com.repopilot.agent.domain.AgentTask;
import com.repopilot.agent.domain.AgentTaskStatus;
import com.repopilot.agent.domain.AgentTaskType;
import com.repopilot.project.domain.Project;
import com.repopilot.project.domain.ProjectStatus;

public record AgentWorkerRunContextResponse(
        Long runId,
        AgentRunStatus runStatus,
        Long taskId,
        AgentTaskStatus taskStatus,
        AgentTaskType taskType,
        String title,
        String description,
        Long projectId,
        String repoUrl,
        String repoFullName,
        String defaultBranch,
        String localPath,
        ProjectStatus projectStatus
) {

    static AgentWorkerRunContextResponse from(AgentRun run) {
        AgentTask task = run.getAgentTask();
        Project project = task.getProject();
        return new AgentWorkerRunContextResponse(
                run.getId(),
                run.getStatus(),
                task.getId(),
                task.getStatus(),
                task.getTaskType(),
                task.getTitle(),
                task.getDescription(),
                project.getId(),
                project.getRepoUrl(),
                project.getRepoFullName(),
                project.getDefaultBranch(),
                project.getLocalPath(),
                project.getStatus()
        );
    }
}
