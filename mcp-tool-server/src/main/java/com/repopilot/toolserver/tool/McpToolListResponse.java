package com.repopilot.toolserver.tool;

import java.util.List;

public record McpToolListResponse(
        String service,
        String protocolVersion,
        int toolCount,
        List<McpToolDefinition> tools
) {
}
