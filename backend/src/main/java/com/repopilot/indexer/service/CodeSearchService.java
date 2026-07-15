package com.repopilot.indexer.service;

import java.util.List;

import com.repopilot.indexer.dto.CodeSearchResponse;
import com.repopilot.indexer.dto.CodeSearchResultResponse;
import com.repopilot.indexer.repository.CodeChunkRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CodeSearchService {

    private static final int MAX_LIMIT = 30;

    private final CodeChunkRepository codeChunkRepository;

    public CodeSearchService(CodeChunkRepository codeChunkRepository) {
        this.codeChunkRepository = codeChunkRepository;
    }

    @Transactional(readOnly = true)
    public CodeSearchResponse search(Long projectId, String query, int limit) {
        String normalizedQuery = query == null ? "" : query.trim();
        int normalizedLimit = Math.max(1, Math.min(limit, MAX_LIMIT));
        if (normalizedQuery.isBlank()) {
            return new CodeSearchResponse(normalizedQuery, normalizedLimit, List.of());
        }
        List<CodeSearchResultResponse> results = codeChunkRepository
                .search(projectId, normalizedQuery, PageRequest.of(0, normalizedLimit))
                .stream()
                .map(CodeSearchResultResponse::from)
                .toList();
        return new CodeSearchResponse(normalizedQuery, normalizedLimit, results);
    }
}

