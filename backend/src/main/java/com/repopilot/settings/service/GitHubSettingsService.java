package com.repopilot.settings.service;

import java.util.ArrayList;
import java.util.List;

import com.repopilot.settings.dto.GitHubSettingsResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class GitHubSettingsService {

    private final boolean enabled;
    private final String apiBaseUrl;
    private final String token;

    public GitHubSettingsService(
            @Value("${repopilot.github.enabled:false}") boolean enabled,
            @Value("${repopilot.github.api-base-url:https://api.github.com}") String apiBaseUrl,
            @Value("${repopilot.github.token:}") String token
    ) {
        this.enabled = enabled;
        this.apiBaseUrl = apiBaseUrl;
        this.token = token;
    }

    public GitHubSettingsResponse current() {
        List<String> missingRequirements = missingRequirements();
        boolean ready = !enabled || missingRequirements.isEmpty();
        return new GitHubSettingsResponse(
                "GITHUB",
                enabled,
                ready,
                enabled ? "REMOTE_GITHUB_PR" : "LOCAL_DRAFT_ONLY",
                apiBaseUrl(),
                isPresent(token),
                enabled,
                !enabled,
                missingRequirements
        );
    }

    private List<String> missingRequirements() {
        List<String> missing = new ArrayList<>();
        if (enabled && !isPresent(token)) {
            missing.add("token");
        }
        return missing;
    }

    private String apiBaseUrl() {
        String value = apiBaseUrl == null || apiBaseUrl.isBlank()
                ? "https://api.github.com"
                : apiBaseUrl.trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    private boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }
}
