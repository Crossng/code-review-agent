package com.repopilot.patch.service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.repopilot.common.ApiException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class ConfiguredCoderModelClient implements CoderModelClient {

    private static final int ERROR_EXCERPT_LIMIT = 2_000;
    private static final int CONTEXT_PREVIEW_LIMIT = 900;
    private static final int CONTEXT_SUMMARY_LIMIT = 300;

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
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

    @Autowired
    public ConfiguredCoderModelClient(
            ObjectMapper objectMapper,
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
        this(
                objectMapper,
                mode,
                fixtureResponse,
                apiBaseUrl,
                apiKey,
                model,
                timeoutSeconds,
                maxCompletionTokens,
                instructionRole,
                organization,
                project,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build()
        );
    }

    ConfiguredCoderModelClient(String mode, String fixtureResponse) {
        this(
                new ObjectMapper(),
                mode,
                fixtureResponse,
                "https://api.openai.com/v1",
                "",
                "",
                120,
                4096,
                "developer",
                "",
                "",
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build()
        );
    }

    ConfiguredCoderModelClient(
            ObjectMapper objectMapper,
            String mode,
            String fixtureResponse,
            String apiBaseUrl,
            String apiKey,
            String model,
            int timeoutSeconds,
            int maxCompletionTokens,
            String instructionRole,
            String organization,
            String project,
            HttpClient httpClient
    ) {
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
        this.mode = mode == null ? "disabled" : mode;
        this.fixtureResponse = fixtureResponse;
        this.apiBaseUrl = apiBaseUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.timeoutSeconds = timeoutSeconds <= 0 ? 120 : timeoutSeconds;
        this.maxCompletionTokens = maxCompletionTokens;
        this.instructionRole = instructionRole;
        this.organization = organization;
        this.project = project;
    }

    @Override
    public Optional<CoderModelResponse> generatePatch(CoderModelRequest request) {
        String normalizedMode = mode.trim().toLowerCase(Locale.ROOT);
        if (normalizedMode.isBlank() || normalizedMode.equals("disabled")) {
            return Optional.empty();
        }
        if (normalizedMode.equals("fixture")) {
            if (fixtureResponse == null || fixtureResponse.isBlank()) {
                throw new ApiException(
                        HttpStatus.CONFLICT,
                        "CODER_MODEL_FIXTURE_EMPTY",
                        "Coder model fixture mode requires repopilot.coder.fixture-response"
                );
            }
            return Optional.of(new CoderModelResponse("LOCAL_FIXTURE", "fixture-coder", fixtureResponse));
        }
        if (normalizedMode.equals("openai") || normalizedMode.equals("openai-compatible")) {
            return Optional.of(generateOpenAiCompatiblePatch(request));
        }
        throw new ApiException(
                HttpStatus.CONFLICT,
                "CODER_MODEL_MODE_UNSUPPORTED",
                "Unsupported coder model mode: " + mode
        );
    }

    private CoderModelResponse generateOpenAiCompatiblePatch(CoderModelRequest request) {
        String configuredApiKey = required(apiKey, "CODER_MODEL_API_KEY_NOT_CONFIGURED", "Coder model API key is not configured");
        String configuredModel = required(model, "CODER_MODEL_MODEL_NOT_CONFIGURED", "Coder model name is not configured");
        try {
            String requestBody = objectMapper.writeValueAsString(chatCompletionRequest(request, configuredModel));
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(apiBaseUrl() + "/chat/completions"))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .header("Authorization", "Bearer " + configuredApiKey)
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody));
            addOptionalHeader(builder, "OpenAI-Organization", organization);
            addOptionalHeader(builder, "OpenAI-Project", project);

            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new ApiException(
                        HttpStatus.BAD_GATEWAY,
                        "CODER_MODEL_REQUEST_FAILED",
                        "Coder model returned HTTP " + response.statusCode() + ": " + excerpt(response.body())
                );
            }
            JsonNode body = objectMapper.readTree(response.body());
            String rawOutput = extractAssistantContent(body);
            String responseModel = body.path("model").asText(configuredModel);
            return new CoderModelResponse("OPENAI_COMPATIBLE", responseModel, rawOutput);
        } catch (IOException exception) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "CODER_MODEL_REQUEST_FAILED", exception.getMessage());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ApiException(HttpStatus.BAD_GATEWAY, "CODER_MODEL_REQUEST_FAILED", "Coder model request interrupted");
        }
    }

    private Map<String, Object> chatCompletionRequest(CoderModelRequest request, String configuredModel) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", configuredModel);
        body.put("messages", List.of(
                Map.of(
                        "role", instructionRole(),
                        "content", coderSystemPrompt()
                ),
                Map.of(
                        "role", "user",
                        "content", coderUserPrompt(request)
                )
        ));
        if (maxCompletionTokens > 0) {
            body.put("max_completion_tokens", maxCompletionTokens);
        }
        return body;
    }

    private String coderSystemPrompt() {
        return """
                You are RepoPilot CoderAgent for Java and Spring Boot repositories.
                Return only one raw unified diff.
                The first non-whitespace characters must be: diff --git
                Do not include Markdown fences, explanations, summaries, or multiple alternatives.
                Keep changes small, compile-safe, and limited to repository-relative paths.
                Do not edit .git, secret files, absolute paths, parent directories, or binary files.
                If the retrieved context is insufficient for a safe source-code change, create only a repository-local planning file under .repopilot/ explaining the missing context and validation needed.
                """;
    }

    private String coderUserPrompt(CoderModelRequest request) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("# Task\n");
        prompt.append("- Task id: ").append(request.taskId()).append('\n');
        prompt.append("- Type: ").append(nullToEmpty(request.taskType())).append('\n');
        prompt.append("- Title: ").append(nullToEmpty(request.taskTitle())).append('\n');
        prompt.append("- Description: ").append(nullToEmpty(request.taskDescription())).append('\n');
        prompt.append("- Repository: ").append(nullToEmpty(request.projectRepo())).append('\n');
        prompt.append("- Base branch: ").append(nullToEmpty(request.defaultBranch())).append("\n\n");
        prompt.append("# Retrieved Context\n");
        if (request.retrievedContext() == null || request.retrievedContext().isEmpty()) {
            prompt.append("No retrieved code context was available.\n");
        } else {
            int index = 1;
            for (CoderContextCandidate candidate : request.retrievedContext()) {
                prompt.append("## Candidate ").append(index++).append('\n');
                prompt.append("- chunkId: ").append(candidate.chunkId()).append('\n');
                prompt.append("- filePath: ").append(nullToEmpty(candidate.filePath())).append('\n');
                prompt.append("- chunkType: ").append(nullToEmpty(candidate.chunkType())).append('\n');
                prompt.append("- symbolType: ").append(nullToEmpty(candidate.symbolType())).append('\n');
                prompt.append("- qualifiedName: ").append(nullToEmpty(candidate.qualifiedName())).append('\n');
                prompt.append("- lines: ").append(lineRange(candidate.startLine(), candidate.endLine())).append('\n');
                prompt.append("- summary: ").append(truncate(candidate.summary(), CONTEXT_SUMMARY_LIMIT)).append('\n');
                prompt.append("```text\n").append(truncate(candidate.preview(), CONTEXT_PREVIEW_LIMIT)).append("\n```\n\n");
            }
        }
        prompt.append("# Output Contract\n");
        prompt.append("Output a single unified diff only. It must pass git apply, RepoPilot patch safety preflight, mvn test, and human review.\n");
        return prompt.toString();
    }

    private String extractAssistantContent(JsonNode body) {
        JsonNode content = body.path("choices").path(0).path("message").path("content");
        String text = textFromContent(content).trim();
        if (text.isBlank()) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "CODER_MODEL_EMPTY_RESPONSE", "Coder model response did not include assistant content");
        }
        return text;
    }

    private String textFromContent(JsonNode content) {
        if (content.isTextual()) {
            return content.asText();
        }
        if (!content.isArray()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        for (JsonNode part : content) {
            JsonNode text = part.path("text");
            if (text.isTextual()) {
                parts.add(text.asText());
            }
        }
        return String.join("", parts);
    }

    private String instructionRole() {
        String role = instructionRole == null ? "developer" : instructionRole.trim().toLowerCase(Locale.ROOT);
        if (role.equals("developer") || role.equals("system")) {
            return role;
        }
        throw new ApiException(
                HttpStatus.CONFLICT,
                "CODER_MODEL_INSTRUCTION_ROLE_UNSUPPORTED",
                "Coder model instruction role must be developer or system"
        );
    }

    private String apiBaseUrl() {
        String trimmed = apiBaseUrl == null || apiBaseUrl.isBlank()
                ? "https://api.openai.com/v1"
                : apiBaseUrl.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private String required(String value, String code, String message) {
        if (value == null || value.isBlank()) {
            throw new ApiException(HttpStatus.CONFLICT, code, message);
        }
        return value.trim();
    }

    private void addOptionalHeader(HttpRequest.Builder builder, String name, String value) {
        if (value != null && !value.isBlank()) {
            builder.header(name, value.trim());
        }
    }

    private String lineRange(Integer startLine, Integer endLine) {
        if (startLine == null && endLine == null) {
            return "unknown";
        }
        if (startLine == null) {
            return "?-" + endLine;
        }
        if (endLine == null) {
            return startLine + "-?";
        }
        return startLine + "-" + endLine;
    }

    private String truncate(String value, int limit) {
        String normalized = nullToEmpty(value).replace("\r\n", "\n");
        if (normalized.length() <= limit) {
            return normalized;
        }
        return normalized.substring(0, limit) + "...";
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
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
}
