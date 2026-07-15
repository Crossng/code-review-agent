package com.repopilot.project.dto;

public record ProjectControllerDownstreamCallResponse(
        String receiverName,
        String componentType,
        String methodName,
        Integer line
) {
}
