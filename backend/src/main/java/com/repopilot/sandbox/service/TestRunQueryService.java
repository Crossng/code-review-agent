package com.repopilot.sandbox.service;

import java.util.List;

import com.repopilot.agent.domain.AgentRun;
import com.repopilot.agent.repository.AgentRunRepository;
import com.repopilot.common.ApiException;
import com.repopilot.sandbox.domain.TestRun;
import com.repopilot.sandbox.repository.TestRunRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TestRunQueryService {

    private final AgentRunRepository agentRunRepository;
    private final TestRunRepository testRunRepository;

    public TestRunQueryService(AgentRunRepository agentRunRepository, TestRunRepository testRunRepository) {
        this.agentRunRepository = agentRunRepository;
        this.testRunRepository = testRunRepository;
    }

    @Transactional(readOnly = true)
    public List<TestRun> listByRun(Long runId, Long userId) {
        AgentRun run = agentRunRepository.findById(runId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "AGENT_RUN_NOT_FOUND", "Agent run not found"));
        if (!run.getAgentTask().getUser().getId().equals(userId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "AGENT_RUN_FORBIDDEN", "Run does not belong to current user");
        }
        return testRunRepository.findByAgentRunIdOrderByCreatedAtDesc(runId);
    }
}
