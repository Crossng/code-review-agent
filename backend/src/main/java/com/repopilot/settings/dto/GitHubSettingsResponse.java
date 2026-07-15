package com.repopilot.settings.dto;

import java.util.List;

public record GitHubSettingsResponse(
        String provider,
        boolean enabled,
        boolean ready,
        String publishMode,
        String apiBaseUrl,
        boolean tokenConfigured,
        boolean remotePublishingEnabled,
        boolean localDraftMode,
        List<String> missingRequirements
) {
}
