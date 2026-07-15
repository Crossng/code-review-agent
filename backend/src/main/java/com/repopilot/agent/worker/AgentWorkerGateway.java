package com.repopilot.agent.worker;

import com.repopilot.agent.domain.AgentRun;

public interface AgentWorkerGateway {

    boolean isEnabled();

    AgentWorkerStartResult startRun(AgentRun run);
}
