package com.repopilot.settings.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.repopilot.settings.dto.CoderSettingsResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class CoderSettingsService {

    private static final List<String> SUPPORTED_MODES = List.of("disabled", "fixture", "openai", "openai-compatible");

    private final String mode;
    private final String fixtureResponse;
    private final String apiBaseUrl;
    private final String apiKey;
    private final String model;
    private final int timeoutSeconds;
    private final int maxCompletionTokens;
    private final String instructionRole;
    private final String organization;
    private final String project;

    public CoderSettingsService(
            @Value("${repopilot.coder.mode:disabled}") String mode,
            @Value("${repopilot.coder.fixture-response:}") String fixtureResponse,
            @Value("${repopilot.coder.api-base-url:https://api.openai.com/v1}") String apiBaseUrl,
            @Value("${repopilot.coder.api-key:}") String apiKey,
            @Value("${repopilot.coder.model:}") String model,
            @Value("${repopilot.coder.timeout-seconds:120}") int timeoutSeconds,
            @Value("${repopilot.coder.max-completion-tokens:4096}") int maxCompletionTokens,
            @Value("${repopilot.coder.instruction-role:developer}") String instructionRole,
            @Value("${repopilot.coder.organization:}") String organization,
            @Value("${repopilot.coder.project:}") String project
    ) {
        this.mode = mode;
        this.fixtureResponse = fixtureResponse;
        this.apiBaseUrl = apiBaseUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.timeoutSeconds = timeoutSeconds;
        this.maxCompletionTokens = maxCompletionTokens;
        this.instructionRole = instructionRole;
        this.organization = organization;
        this.project = project;
    }

    public CoderSettingsResponse current() {
        String normalizedMode = normalizeMode(mode);
        String provider = provider(normalizedMode);
        List<String> missingRequirements = missingRequirements(normalizedMode);
        boolean enabled = !normalizedMode.equals("disabled");
        return new CoderSettingsResponse(
                normalizedMode,
                provider,
                enabled,
                missingRequirements.isEmpty(),
                blankToNull(model),
                apiBaseUrl(),
                isPresent(apiKey),
                isPresent(fixtureResponse),
                Math.max(1, timeoutSeconds),
                maxCompletionTokens,
                instructionRole(),
                isPresent(organization),
                isPresent(project),
                missingRequirements,
                SUPPORTED_MODES
        );
    }

    private List<String> missingRequirements(String normalizedMode) {
        List<String> missing = new ArrayList<>();
        if (normalizedMode.equals("fixture")) {
            if (!isPresent(fixtureResponse)) {
                missing.add("fixture-response");
            }
            return missing;
        }
        if (normalizedMode.equals("openai") || normalizedMode.equals("openai-compatible")) {
            if (!isPresent(apiKey)) {
                missing.add("api-key");
            }
            if (!isPresent(model)) {
                missing.add("model");
            }
            if (!isSupportedInstructionRole()) {
                missing.add("instruction-role");
            }
            return missing;
        }
        if (!normalizedMode.equals("disabled")) {
            missing.add("supported-mode");
        }
        return missing;
    }

    private String provider(String normalizedMode) {
        return switch (normalizedMode) {
            case "disabled" -> "NONE";
            case "fixture" -> "LOCAL_FIXTURE";
            case "openai", "openai-compatible" -> "OPENAI_COMPATIBLE";
            default -> "UNSUPPORTED";
        };
    }

    private String normalizeMode(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return normalized.isBlank() ? "disabled" : normalized;
    }

    private String apiBaseUrl() {
        String value = apiBaseUrl == null || apiBaseUrl.isBlank()
                ? "https://api.openai.com/v1"
                : apiBaseUrl.trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    private String instructionRole() {
        String role = instructionRole == null || instructionRole.isBlank()
                ? "developer"
                : instructionRole.trim().toLowerCase(Locale.ROOT);
        if (role.equals("developer") || role.equals("system")) {
            return role;
        }
        return role;
    }

    private boolean isSupportedInstructionRole() {
        String role = instructionRole();
        return role.equals("developer") || role.equals("system");
    }

    private boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }

    private String blankToNull(String value) {
        return isPresent(value) ? value.trim() : null;
    }
}
