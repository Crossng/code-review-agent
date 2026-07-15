package com.repopilot.settings.dto;

import java.util.List;

public record CoderSettingsResponse(
        String mode,
        String provider,
        boolean enabled,
        boolean ready,
        String model,
        String apiBaseUrl,
        boolean apiKeyConfigured,
        boolean fixtureConfigured,
        int timeoutSeconds,
        int maxCompletionTokens,
        String instructionRole,
        boolean organizationConfigured,
        boolean projectConfigured,
        List<String> missingRequirements,
        List<String> supportedModes
) {
}
