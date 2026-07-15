package com.repopilot.patch.service;

import java.util.List;

import com.repopilot.agent.domain.AgentTask;
import com.repopilot.agent.repository.AgentTaskRepository;
import com.repopilot.common.ApiException;
import com.repopilot.patch.domain.PatchRecord;
import com.repopilot.patch.repository.PatchRecordRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PatchQueryService {

    private final PatchRecordRepository patchRecordRepository;
    private final AgentTaskRepository agentTaskRepository;

    public PatchQueryService(PatchRecordRepository patchRecordRepository, AgentTaskRepository agentTaskRepository) {
        this.patchRecordRepository = patchRecordRepository;
        this.agentTaskRepository = agentTaskRepository;
    }

    @Transactional(readOnly = true)
    public List<PatchRecord> listByTask(Long taskId, Long userId) {
        validateTaskOwner(taskId, userId);
        return patchRecordRepository.findByAgentTaskIdOrderByCreatedAtDesc(taskId);
    }

    @Transactional(readOnly = true)
    public PatchRecord latest(Long taskId, Long userId) {
        validateTaskOwner(taskId, userId);
        return patchRecordRepository.findFirstByAgentTaskIdOrderByCreatedAtDesc(taskId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PATCH_NOT_FOUND", "Patch not found"));
    }

    private void validateTaskOwner(Long taskId, Long userId) {
        AgentTask task = agentTaskRepository.findById(taskId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "AGENT_TASK_NOT_FOUND", "Agent task not found"));
        if (!task.getUser().getId().equals(userId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "AGENT_TASK_FORBIDDEN", "Task does not belong to current user");
        }
    }
}
