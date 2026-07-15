package com.repopilot.toolcall.service;

import java.util.List;

import com.repopilot.agent.domain.AgentRun;
import com.repopilot.agent.repository.AgentRunRepository;
import com.repopilot.common.ApiException;
import com.repopilot.toolcall.domain.ToolCallLog;
import com.repopilot.toolcall.repository.ToolCallLogRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ToolCallQueryService {

    private final AgentRunRepository agentRunRepository;
    private final ToolCallLogRepository toolCallLogRepository;

    public ToolCallQueryService(AgentRunRepository agentRunRepository, ToolCallLogRepository toolCallLogRepository) {
        this.agentRunRepository = agentRunRepository;
        this.toolCallLogRepository = toolCallLogRepository;
    }

    @Transactional(readOnly = true)
    public List<ToolCallLog> listByRun(Long runId, Long userId) {
        AgentRun run = agentRunRepository.findById(runId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "AGENT_RUN_NOT_FOUND", "Agent run not found"));
        if (!run.getAgentTask().getUser().getId().equals(userId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "AGENT_RUN_FORBIDDEN", "Run does not belong to current user");
        }
        return toolCallLogRepository.findByAgentRunIdOrderByStartedAtAsc(runId);
    }
}
