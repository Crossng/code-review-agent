package com.repopilot.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public record RetryAuditSummaryResponse(
        int attemptCount,
        boolean recovered,
        String firstFailureType,
        String firstFailureMessage
) {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public static RetryAuditSummaryResponse fromJson(String json, boolean recovered) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(json);
            JsonNode attempts = root.path("retryAttempts");
            int attemptCount = root.path("retryAttemptCount").isNumber()
                    ? root.path("retryAttemptCount").asInt()
                    : attempts.isArray() ? attempts.size() : 0;
            if (attemptCount <= 0) {
                return null;
            }
            JsonNode first = attempts.isArray() && attempts.size() > 0 ? attempts.get(0) : OBJECT_MAPPER.createObjectNode();
            return new RetryAuditSummaryResponse(
                    attemptCount,
                    recovered,
                    text(first, "errorType"),
                    truncate(text(first, "message"), 400)
            );
        } catch (JsonProcessingException exception) {
            return null;
        }
    }

    private static String text(JsonNode node, String fieldName) {
        JsonNode value = node.path(fieldName);
        return value.isTextual() && !value.asText().isBlank() ? value.asText() : null;
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength - 1) + "...";
    }
}
