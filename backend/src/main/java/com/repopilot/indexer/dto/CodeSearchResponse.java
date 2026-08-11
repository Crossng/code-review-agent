package com.repopilot.indexer.dto;

import java.util.List;

public record CodeSearchResponse(
        String query,
        int limit,
        String retrievalMode,
        String retrievalModeLabel,
        String embeddingStatus,
        String embeddingProvider,
        String embeddingModel,
        List<CodeSearchResultResponse> results
) {

    public CodeSearchResponse(String query, int limit, List<CodeSearchResultResponse> results) {
        this(query, limit, "KEYWORD", "关键词检索", "DISABLED", null, null, results);
    }
}
