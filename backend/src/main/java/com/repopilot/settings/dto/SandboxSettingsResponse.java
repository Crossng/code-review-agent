package com.repopilot.settings.dto;

import java.util.List;

public record SandboxSettingsResponse(
        boolean ready,
        String dockerImage,
        boolean dockerImageConfigured,
        long timeoutSeconds,
        String workspaceRoot,
        boolean workspaceRootExists,
        boolean workspaceRootWritable,
        String mavenCachePath,
        boolean mavenCacheExists,
        boolean mavenCacheWritable,
        boolean dockerCheckEnabled,
        boolean dockerAvailable,
        String dockerVersion,
        List<String> missingRequirements,
        List<SandboxSettingsCheckResponse> checks
) {
}
