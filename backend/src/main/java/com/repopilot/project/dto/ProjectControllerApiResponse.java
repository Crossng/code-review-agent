package com.repopilot.project.dto;

import java.util.List;

public record ProjectControllerApiResponse(
        String filePath,
        String controllerName,
        String qualifiedControllerName,
        String methodName,
        String httpMethod,
        String path,
        String requestType,
        List<ProjectControllerApiParameterResponse> parameters,
        List<ProjectControllerServiceCallResponse> serviceCalls,
        String responseType,
        List<String> securityAnnotations,
        Integer riskScore,
        String riskLevel,
        List<ProjectControllerRiskHintResponse> riskHints,
        Integer startLine,
        Integer endLine
) {
}
