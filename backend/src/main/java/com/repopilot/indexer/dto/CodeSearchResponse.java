package com.repopilot.indexer.dto;

import java.util.List;

public record CodeSearchResponse(
        String query,
        int limit,
        List<CodeSearchResultResponse> results
) {
}

