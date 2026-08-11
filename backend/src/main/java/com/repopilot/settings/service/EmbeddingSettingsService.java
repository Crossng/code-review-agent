package com.repopilot.settings.service;

import com.repopilot.indexer.embedding.EmbeddingConfiguration;
import com.repopilot.settings.dto.EmbeddingSettingsResponse;
import org.springframework.stereotype.Service;

@Service
public class EmbeddingSettingsService {

    private final EmbeddingConfiguration configuration;

    public EmbeddingSettingsService(EmbeddingConfiguration configuration) {
        this.configuration = configuration;
    }

    public EmbeddingSettingsResponse current() {
        return new EmbeddingSettingsResponse(
                configuration.mode(),
                configuration.provider(),
                configuration.enabled(),
                configuration.operational(),
                configuration.embeddingReady(),
                emptyToNull(configuration.model()),
                configuration.apiBaseUrl(),
                configuration.apiKeyConfigured(),
                configuration.apiKeyRequired(),
                configuration.timeoutSeconds(),
                configuration.batchSize(),
                configuration.maxInputChars(),
                configuration.keywordWeight(),
                configuration.vectorWeight(),
                configuration.minimumSimilarity(),
                "KEYWORD",
                configuration.missingRequirements(),
                configuration.supportedModes()
        );
    }

    private String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
