package com.repopilot.project.dto;

public record ProjectControllerApiParameterResponse(
        String name,
        String source,
        String type,
        boolean required,
        String defaultValue
) {
}
