package com.repopilot.project.dto;

import java.util.List;

public record ProjectControllerServiceCallResponse(
        String receiverName,
        String serviceType,
        String methodName,
        Integer line,
        List<ProjectControllerDownstreamCallResponse> downstreamCalls
) {
}
