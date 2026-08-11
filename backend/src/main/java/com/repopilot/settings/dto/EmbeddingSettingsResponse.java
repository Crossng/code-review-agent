package com.repopilot.settings.dto;

import java.util.List;

public record EmbeddingSettingsResponse(
        String mode,
        String provider,
        boolean enabled,
        boolean ready,
        boolean embeddingAvailable,
        String model,
        String apiBaseUrl,
        boolean apiKeyConfigured,
        boolean apiKeyRequired,
        int timeoutSeconds,
        int batchSize,
        int maxInputChars,
        double keywordWeight,
        double vectorWeight,
        double minimumSimilarity,
        String fallbackMode,
        List<String> missingRequirements,
        List<String> supportedModes
) {
}
