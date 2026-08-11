package com.repopilot.toolserver.tool;

import java.util.Map;

public record ToolValidationRequest(
        Map<String, Object> arguments
) {
}
