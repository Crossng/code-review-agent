package com.repopilot.agent.worker;

import com.repopilot.agent.domain.AgentRun;

public interface AgentWorkerGateway {

    boolean isEnabled();

    boolean isPrimaryExecutionReady();

    AgentWorkerStartResult startRun(AgentRun run);
}
