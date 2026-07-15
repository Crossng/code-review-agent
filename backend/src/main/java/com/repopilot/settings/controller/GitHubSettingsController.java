package com.repopilot.settings.controller;

import com.repopilot.common.ApiResponse;
import com.repopilot.settings.dto.GitHubSettingsResponse;
import com.repopilot.settings.service.GitHubSettingsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/settings/github")
public class GitHubSettingsController {

    private final GitHubSettingsService gitHubSettingsService;

    public GitHubSettingsController(GitHubSettingsService gitHubSettingsService) {
        this.gitHubSettingsService = gitHubSettingsService;
    }

    @GetMapping
    public ApiResponse<GitHubSettingsResponse> get() {
        return ApiResponse.ok(gitHubSettingsService.current());
    }
}
