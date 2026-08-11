package com.repopilot.indexer.embedding;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class EmbeddingConfiguration {

    private static final List<String> SUPPORTED_MODES = List.of("disabled", "openai", "openai-compatible");

    private final String mode;
    private final String apiBaseUrl;
    private final String apiKey;
    private final String model;
    private final int timeoutSeconds;
    private final int batchSize;
    private final int maxInputChars;
    private final double keywordWeight;
    private final double vectorWeight;
    private final double minimumSimilarity;

    public EmbeddingConfiguration(
            @Value("${repopilot.embedding.mode:disabled}") String mode,
            @Value("${repopilot.embedding.api-base-url:https://api.openai.com/v1}") String apiBaseUrl,
            @Value("${repopilot.embedding.api-key:}") String apiKey,
            @Value("${repopilot.embedding.model:}") String model,
            @Value("${repopilot.embedding.timeout-seconds:60}") int timeoutSeconds,
            @Value("${repopilot.embedding.batch-size:32}") int batchSize,
            @Value("${repopilot.embedding.max-input-chars:12000}") int maxInputChars,
            @Value("${repopilot.embedding.keyword-weight:0.45}") double keywordWeight,
            @Value("${repopilot.embedding.vector-weight:0.55}") double vectorWeight,
            @Value("${repopilot.embedding.minimum-similarity:0.20}") double minimumSimilarity
    ) {
        this.mode = mode;
        this.apiBaseUrl = apiBaseUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.timeoutSeconds = timeoutSeconds;
        this.batchSize = batchSize;
        this.maxInputChars = maxInputChars;
        this.keywordWeight = keywordWeight;
        this.vectorWeight = vectorWeight;
        this.minimumSimilarity = minimumSimilarity;
    }

    public String mode() {
        String normalized = mode == null ? "" : mode.trim().toLowerCase(Locale.ROOT);
        return normalized.isBlank() ? "disabled" : normalized;
    }

    public String provider() {
        return switch (mode()) {
            case "disabled" -> "NONE";
            case "openai", "openai-compatible" -> "OPENAI_COMPATIBLE";
            default -> "UNSUPPORTED";
        };
    }

    public boolean enabled() {
        return !mode().equals("disabled");
    }

    public boolean embeddingReady() {
        return enabled() && missingRequirements().isEmpty();
    }

    public boolean operational() {
        return mode().equals("disabled") || missingRequirements().isEmpty();
    }

    public String apiBaseUrl() {
        String value = apiBaseUrl == null ? "" : apiBaseUrl.trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    public String apiKey() {
        return apiKey == null ? "" : apiKey.trim();
    }

    public boolean apiKeyConfigured() {
        return !apiKey().isBlank();
    }

    public boolean apiKeyRequired() {
        return mode().equals("openai");
    }

    public String model() {
        return model == null ? "" : model.trim();
    }

    public int timeoutSeconds() {
        return Math.max(1, timeoutSeconds);
    }

    public int batchSize() {
        return Math.max(1, Math.min(batchSize, 100));
    }

    public int maxInputChars() {
        return Math.max(256, maxInputChars);
    }

    public double keywordWeight() {
        return normalizedWeight(keywordWeight);
    }

    public double vectorWeight() {
        return normalizedWeight(vectorWeight);
    }

    public double minimumSimilarity() {
        return Math.max(-1.0, Math.min(1.0, minimumSimilarity));
    }

    public List<String> missingRequirements() {
        List<String> missing = new ArrayList<>();
        String normalizedMode = mode();
        if (normalizedMode.equals("disabled")) {
            return missing;
        }
        if (!SUPPORTED_MODES.contains(normalizedMode)) {
            missing.add("supported-mode");
            return missing;
        }
        if (model().isBlank()) {
            missing.add("model");
        }
        if (apiBaseUrl().isBlank()) {
            missing.add("api-base-url");
        }
        if (apiKeyRequired() && !apiKeyConfigured()) {
            missing.add("api-key");
        }
        if (!validWeights()) {
            missing.add("search-weights");
        }
        return missing;
    }

    public List<String> supportedModes() {
        return SUPPORTED_MODES;
    }

    private boolean validWeights() {
        return Double.isFinite(keywordWeight)
                && Double.isFinite(vectorWeight)
                && keywordWeight >= 0
                && vectorWeight >= 0
                && keywordWeight + vectorWeight > 0;
    }

    private double normalizedWeight(double weight) {
        if (!validWeights()) {
            return 0.5;
        }
        return weight / (keywordWeight + vectorWeight);
    }
}
