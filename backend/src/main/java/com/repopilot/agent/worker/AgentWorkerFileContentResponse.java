package com.repopilot.agent.worker;

public record AgentWorkerFileContentResponse(
        String path,
        String content,
        long size
) {
}
