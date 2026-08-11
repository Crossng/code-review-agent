package com.repopilot.settings.dto;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

public record McpToolArgumentResponse(
        String name,
        String type,
        boolean required,
        String description,
        JsonNode defaultValue,
        List<String> allowedValues
) {
}
