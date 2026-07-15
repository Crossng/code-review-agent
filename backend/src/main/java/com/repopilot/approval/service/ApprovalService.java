package com.repopilot.approval.service;

import java.util.List;
import java.util.Set;

import com.repopilot.agent.domain.AgentTask;
import com.repopilot.agent.domain.AgentTaskStatus;
import com.repopilot.agent.repository.AgentTaskRepository;
import com.repopilot.agent.service.ProjectWriteGuardService;
import com.repopilot.approval.domain.ApprovalAction;
import com.repopilot.approval.domain.ApprovalRecord;
import com.repopilot.approval.repository.ApprovalRecordRepository;
import com.repopilot.common.ApiException;
import com.repopilot.patch.domain.PatchRecord;
import com.repopilot.patch.domain.PatchStatus;
import com.repopilot.patch.repository.PatchRecordRepository;
import com.repopilot.user.domain.User;
import com.repopilot.user.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ApprovalService {

    private final ApprovalRecordRepository approvalRecordRepository;
    private final AgentTaskRepository agentTaskRepository;
    private final PatchRecordRepository patchRecordRepository;
    private final UserRepository userRepository;
    private final ProjectWriteGuardService projectWriteGuardService;

    public ApprovalService(
            ApprovalRecordRepository approvalRecordRepository,
            AgentTaskRepository agentTaskRepository,
            PatchRecordRepository patchRecordRepository,
            UserRepository userRepository,
            ProjectWriteGuardService projectWriteGuardService
    ) {
        this.approvalRecordRepository = approvalRecordRepository;
        this.agentTaskRepository = agentTaskRepository;
        this.patchRecordRepository = patchRecordRepository;
        this.userRepository = userRepository;
        this.projectWriteGuardService = projectWriteGuardService;
    }

    @Transactional
    public ApprovalRecord approve(Long taskId, Long patchId, Long userId, String comment) {
        ApprovalContext context = context(taskId, patchId, userId);
        ensureWaitingHumanApproval(context.task());
        ensureApprovable(context.patch());
        projectWriteGuardService.ensureProjectWriteSlot(
                context.task(),
                Set.of(AgentTaskStatus.WAITING_HUMAN_APPROVAL),
                "Task is not waiting for human approval"
        );
        context.patch().approve();
        context.task().setStatus(AgentTaskStatus.CREATING_PULL_REQUEST);
        return save(context, ApprovalAction.APPROVE, comment);
    }

    @Transactional
    public ApprovalRecord reject(Long taskId, Long patchId, Long userId, String comment) {
        ApprovalContext context = context(taskId, patchId, userId);
        ensureWaitingHumanApproval(context.task());
        ensureApprovable(context.patch());
        context.patch().reject();
        context.task().setStatus(AgentTaskStatus.CANCELLED);
        return save(context, ApprovalAction.REJECT, comment);
    }

    @Transactional(readOnly = true)
    public List<ApprovalRecord> list(Long taskId, Long userId) {
        AgentTask task = task(taskId);
        ensureOwner(task, userId);
        return approvalRecordRepository.findByAgentTaskIdOrderByCreatedAtDesc(taskId);
    }

    private ApprovalRecord save(ApprovalContext context, ApprovalAction action, String comment) {
        patchRecordRepository.save(context.patch());
        agentTaskRepository.save(context.task());
        return approvalRecordRepository.save(new ApprovalRecord(
                context.task(),
                context.patch(),
                context.user(),
                action,
                comment
        ));
    }

    private ApprovalContext context(Long taskId, Long patchId, Long userId) {
        AgentTask task = task(taskId);
        ensureOwner(task, userId);
        PatchRecord patch = patchRecordRepository.findById(patchId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PATCH_NOT_FOUND", "Patch not found"));
        if (!patch.getAgentTask().getId().equals(task.getId())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PATCH_TASK_MISMATCH", "Patch does not belong to task");
        }
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "User not found"));
        return new ApprovalContext(task, patch, user);
    }

    private AgentTask task(Long taskId) {
        return agentTaskRepository.findById(taskId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "AGENT_TASK_NOT_FOUND", "Agent task not found"));
    }

    private void ensureOwner(AgentTask task, Long userId) {
        if (!task.getUser().getId().equals(userId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "AGENT_TASK_FORBIDDEN", "Task does not belong to current user");
        }
    }

    private void ensureApprovable(PatchRecord patch) {
        if (patch.getStatus() != PatchStatus.GENERATED && patch.getStatus() != PatchStatus.APPLIED) {
            throw new ApiException(HttpStatus.CONFLICT, "PATCH_INVALID_STATUS", "Only GENERATED or APPLIED patches can be approved or rejected");
        }
    }

    private void ensureWaitingHumanApproval(AgentTask task) {
        if (task.getStatus() != AgentTaskStatus.WAITING_HUMAN_APPROVAL) {
            throw new ApiException(HttpStatus.CONFLICT, "AGENT_INVALID_STATUS", "Task is not waiting for human approval");
        }
    }

    private record ApprovalContext(AgentTask task, PatchRecord patch, User user) {
    }
}
