package com.repopilot.toolserver.tool;

import java.util.List;

public record McpToolArgumentDefinition(
        String name,
        String type,
        boolean required,
        String description,
        Object defaultValue,
        List<String> allowedValues
) {
}
