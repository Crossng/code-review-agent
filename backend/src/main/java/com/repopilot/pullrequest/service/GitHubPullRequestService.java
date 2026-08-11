package com.repopilot.pullrequest.service;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.repopilot.common.ApiException;
import com.repopilot.project.domain.Project;
import com.repopilot.pullrequest.domain.PullRequestPublishOutcome;
import com.repopilot.pullrequest.domain.PullRequestRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class GitHubPullRequestService {

    private static final int ERROR_EXCERPT_LIMIT = 2_000;

    private final PullRequestGitService pullRequestGitService;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final boolean enabled;
    private final String apiBaseUrl;
    private final String token;

    public GitHubPullRequestService(
            PullRequestGitService pullRequestGitService,
            ObjectMapper objectMapper,
            @Value("${repopilot.github.enabled:false}") boolean enabled,
            @Value("${repopilot.github.api-base-url:https://api.github.com}") String apiBaseUrl,
            @Value("${repopilot.github.token:}") String token
    ) {
        this.pullRequestGitService = pullRequestGitService;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.apiBaseUrl = apiBaseUrl;
        this.token = token;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .build();
    }

    public boolean shouldPublish(Project project) {
        return enabled && repository(project).isPresent();
    }

    public boolean isRemotePublishingEnabled() {
        return enabled;
    }

    public boolean isRepositoryEligible(Project project) {
        return repository(project).isPresent();
    }

    public boolean isTokenConfigured() {
        return token != null && !token.isBlank();
    }

    public GitHubPullRequest publish(Project project, PullRequestRecord record) {
        GitHubRepository repository = repository(project)
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "PROJECT_NOT_GITHUB_REPOSITORY", "项目仓库不是 github.com 托管仓库"));
        ensureLocalGitReady(record);
        String authToken = token();
        pullRequestGitService.pushBranch(project, record.getTargetBranch(), authToken);
        record.markRemotePushed();

        Optional<GitHubPullRequest> existing = findOpenPullRequest(
                repository,
                record,
                authToken,
                PullRequestPublishOutcome.REMOTE_REUSED_EXISTING
        );
        if (existing.isPresent()) {
            return existing.get();
        }
        return createPullRequest(repository, record, authToken);
    }

    private GitHubPullRequest createPullRequest(GitHubRepository repository, PullRequestRecord record, String authToken) {
        ApiException failure;
        try {
            String requestBody = objectMapper.writeValueAsString(Map.of(
                    "title", record.getTitle(),
                    "head", record.getTargetBranch(),
                    "base", record.getBaseBranch(),
                    "body", record.getBody()
            ));
            HttpRequest request = githubRequestBuilder(URI.create(pullsEndpoint(repository)), authToken)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 201) {
                return pullRequestFrom(
                        objectMapper.readTree(response.body()),
                        PullRequestPublishOutcome.REMOTE_CREATED
                );
            }

            failure = createFailure(response.statusCode(), response.body());
            if (isAmbiguousCreateFailure(response.statusCode())) {
                return reconcileAfterCreateFailure(repository, record, authToken, failure);
            }
            throw failure;
        } catch (IOException exception) {
            failure = new ApiException(
                    HttpStatus.BAD_GATEWAY,
                    "GITHUB_PR_CREATE_FAILED",
                    "GitHub PR 创建请求失败：" + exceptionMessage(exception)
            );
            return reconcileAfterCreateFailure(repository, record, authToken, failure);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ApiException(HttpStatus.BAD_GATEWAY, "GITHUB_PR_CREATE_FAILED", "GitHub 请求被中断");
        }
    }

    private Optional<GitHubPullRequest> findOpenPullRequest(
            GitHubRepository repository,
            PullRequestRecord record,
            String authToken,
            PullRequestPublishOutcome publishOutcome
    ) {
        String query = "state=open"
                + "&head=" + encode(repository.owner() + ":" + record.getTargetBranch())
                + "&base=" + encode(record.getBaseBranch())
                + "&per_page=1";
        HttpRequest request = githubRequestBuilder(
                URI.create(pullsEndpoint(repository) + "?" + query),
                authToken
        ).GET().build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new ApiException(
                        HttpStatus.BAD_GATEWAY,
                        "GITHUB_PR_LOOKUP_FAILED",
                        "GitHub PR 对账返回 HTTP " + response.statusCode() + "：" + excerpt(response.body())
                );
            }
            JsonNode body = objectMapper.readTree(response.body());
            if (!body.isArray()) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "GITHUB_PR_LOOKUP_FAILED", "GitHub PR 对账响应不是数组");
            }
            for (JsonNode candidate : body) {
                if (matchesBranches(candidate, record)) {
                    return Optional.of(pullRequestFrom(candidate, publishOutcome));
                }
            }
            return Optional.empty();
        } catch (IOException exception) {
            throw new ApiException(
                    HttpStatus.BAD_GATEWAY,
                    "GITHUB_PR_LOOKUP_FAILED",
                    "GitHub PR 对账请求失败：" + exceptionMessage(exception)
            );
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ApiException(HttpStatus.BAD_GATEWAY, "GITHUB_PR_LOOKUP_FAILED", "GitHub PR 对账请求被中断");
        }
    }

    private GitHubPullRequest reconcileAfterCreateFailure(
            GitHubRepository repository,
            PullRequestRecord record,
            String authToken,
            ApiException createFailure
    ) {
        try {
            Optional<GitHubPullRequest> reconciled = findOpenPullRequest(
                    repository,
                    record,
                    authToken,
                    PullRequestPublishOutcome.REMOTE_RECONCILED
            );
            if (reconciled.isPresent()) {
                return reconciled.get();
            }
        } catch (ApiException reconciliationFailure) {
            if (Thread.currentThread().isInterrupted()) {
                throw reconciliationFailure;
            }
        }
        throw createFailure;
    }

    private HttpRequest.Builder githubRequestBuilder(URI uri, String authToken) {
        return HttpRequest.newBuilder()
                .uri(uri)
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + authToken)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28");
    }

    private String pullsEndpoint(GitHubRepository repository) {
        return apiBaseUrl() + "/repos/" + repository.owner() + "/" + repository.name() + "/pulls";
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private boolean matchesBranches(JsonNode candidate, PullRequestRecord record) {
        return record.getTargetBranch().equals(candidate.path("head").path("ref").asText())
                && record.getBaseBranch().equals(candidate.path("base").path("ref").asText());
    }

    private GitHubPullRequest pullRequestFrom(JsonNode body, PullRequestPublishOutcome publishOutcome) {
        int number = body.path("number").asInt(0);
        String url = body.path("html_url").asText(null);
        if (number <= 0 || url == null || url.isBlank()) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "GITHUB_PR_RESPONSE_INVALID", "GitHub 响应没有包含 PR 编号或 URL");
        }
        return new GitHubPullRequest(number, url, publishOutcome);
    }

    private ApiException createFailure(int statusCode, String body) {
        return new ApiException(
                HttpStatus.BAD_GATEWAY,
                "GITHUB_PR_CREATE_FAILED",
                "GitHub 返回 HTTP " + statusCode + "：" + excerpt(body)
        );
    }

    private boolean isAmbiguousCreateFailure(int statusCode) {
        return statusCode == 408 || statusCode == 409 || statusCode == 422 || statusCode == 429 || statusCode >= 500;
    }

    private String exceptionMessage(IOException exception) {
        return exception.getMessage() == null || exception.getMessage().isBlank()
                ? exception.getClass().getSimpleName()
                : exception.getMessage();
    }

    private Optional<GitHubRepository> repository(Project project) {
        String repoUrl = project.getRepoUrl();
        if (repoUrl == null || repoUrl.isBlank()) {
            return Optional.empty();
        }
        if (repoUrl.startsWith("git@github.com:")) {
            return parsePath(repoUrl.substring("git@github.com:".length()));
        }
        try {
            URI uri = URI.create(repoUrl);
            if (!"github.com".equalsIgnoreCase(uri.getHost())) {
                return Optional.empty();
            }
            return parsePath(uri.getPath());
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private Optional<GitHubRepository> parsePath(String path) {
        if (path == null || path.isBlank()) {
            return Optional.empty();
        }
        String normalized = path.replaceFirst("^/", "");
        if (normalized.endsWith(".git")) {
            normalized = normalized.substring(0, normalized.length() - 4);
        }
        String[] parts = normalized.split("/");
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new GitHubRepository(parts[0], parts[1]));
    }

    private String token() {
        if (token == null || token.isBlank()) {
            throw new ApiException(HttpStatus.CONFLICT, "GITHUB_TOKEN_NOT_CONFIGURED", "已启用 GitHub 发布，但尚未配置 token");
        }
        return token.trim();
    }

    private void ensureLocalGitReady(PullRequestRecord record) {
        if (isBlank(record.getBaseBranch()) || isBlank(record.getTargetBranch()) || isBlank(record.getCommitSha())) {
            throw new ApiException(HttpStatus.CONFLICT, "PULL_REQUEST_LOCAL_GIT_NOT_READY", "PR 记录缺少本地分支或提交元数据");
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String apiBaseUrl() {
        String trimmed = apiBaseUrl == null || apiBaseUrl.isBlank()
                ? "https://api.github.com"
                : apiBaseUrl.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private String excerpt(String value) {
        if (value == null) {
            return "";
        }
        if (value.length() <= ERROR_EXCERPT_LIMIT) {
            return value;
        }
        return value.substring(0, ERROR_EXCERPT_LIMIT);
    }

    private record GitHubRepository(String owner, String name) {
    }

    public record GitHubPullRequest(
            Integer number,
            String url,
            PullRequestPublishOutcome publishOutcome
    ) {
    }
}
