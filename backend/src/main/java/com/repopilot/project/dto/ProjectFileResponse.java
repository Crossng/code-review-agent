package com.repopilot.project.dto;

public record ProjectFileResponse(
        String path,
        String type,
        long size
) {
}

