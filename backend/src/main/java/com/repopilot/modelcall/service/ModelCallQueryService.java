package com.repopilot.modelcall.service;

import java.util.List;

import com.repopilot.agent.domain.AgentRun;
import com.repopilot.agent.repository.AgentRunRepository;
import com.repopilot.common.ApiException;
import com.repopilot.modelcall.domain.ModelCallLog;
import com.repopilot.modelcall.repository.ModelCallLogRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ModelCallQueryService {

    private final AgentRunRepository agentRunRepository;
    private final ModelCallLogRepository modelCallLogRepository;

    public ModelCallQueryService(AgentRunRepository agentRunRepository, ModelCallLogRepository modelCallLogRepository) {
        this.agentRunRepository = agentRunRepository;
        this.modelCallLogRepository = modelCallLogRepository;
    }

    @Transactional(readOnly = true)
    public List<ModelCallLog> listByRun(Long runId, Long userId) {
        AgentRun run = agentRunRepository.findById(runId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "AGENT_RUN_NOT_FOUND", "Agent run not found"));
        if (!run.getAgentTask().getUser().getId().equals(userId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "AGENT_RUN_FORBIDDEN", "Run does not belong to current user");
        }
        return modelCallLogRepository.findByAgentRunIdOrderByStartedAtAsc(runId);
    }
}
