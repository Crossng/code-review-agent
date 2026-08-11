package com.repopilot.indexer.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.repopilot.indexer.domain.CodeChunk;
import com.repopilot.indexer.dto.CodeSearchResponse;
import com.repopilot.indexer.dto.CodeSearchResultResponse;
import com.repopilot.indexer.embedding.CodeEmbeddingService;
import com.repopilot.indexer.embedding.CodeEmbeddingService.SemanticSearchResult;
import com.repopilot.indexer.embedding.EmbeddingConfiguration;
import com.repopilot.indexer.repository.CodeChunkRepository;
import com.repopilot.indexer.repository.CodeEmbeddingRepository.VectorMatch;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CodeSearchService {

    private static final int MAX_LIMIT = 30;
    private static final int MAX_CANDIDATE_LIMIT = 90;
    private static final int RRF_CONSTANT = 60;

    private final CodeChunkRepository codeChunkRepository;
    private final CodeEmbeddingService codeEmbeddingService;
    private final EmbeddingConfiguration embeddingConfiguration;

    public CodeSearchService(
            CodeChunkRepository codeChunkRepository,
            CodeEmbeddingService codeEmbeddingService,
            EmbeddingConfiguration embeddingConfiguration
    ) {
        this.codeChunkRepository = codeChunkRepository;
        this.codeEmbeddingService = codeEmbeddingService;
        this.embeddingConfiguration = embeddingConfiguration;
    }

    @Transactional(readOnly = true)
    public CodeSearchResponse search(Long projectId, String query, int limit) {
        String normalizedQuery = query == null ? "" : query.trim();
        int normalizedLimit = Math.max(1, Math.min(limit, MAX_LIMIT));
        if (normalizedQuery.isBlank()) {
            return response(
                    normalizedQuery,
                    normalizedLimit,
                    "KEYWORD",
                    "SKIPPED_EMPTY_QUERY",
                    embeddingConfiguration.provider(),
                    emptyToNull(embeddingConfiguration.model()),
                    List.of()
            );
        }

        int candidateLimit = Math.min(MAX_CANDIDATE_LIMIT, Math.max(24, normalizedLimit * 4));
        SemanticSearchResult semantic = codeEmbeddingService.search(projectId, normalizedQuery, candidateLimit);
        List<CodeChunk> keywordChunks = codeChunkRepository.search(
                projectId,
                normalizedQuery,
                PageRequest.of(0, candidateLimit)
        );
        List<CodeSearchResultResponse> results = merge(keywordChunks, semantic.matches(), normalizedLimit);
        String retrievalMode = retrievalMode(keywordChunks, semantic);
        return response(
                normalizedQuery,
                normalizedLimit,
                retrievalMode,
                semantic.status(),
                semantic.provider(),
                semantic.model(),
                results
        );
    }

    private List<CodeSearchResultResponse> merge(
            List<CodeChunk> keywordChunks,
            List<VectorMatch> vectorMatches,
            int limit
    ) {
        Map<Long, CodeChunk> chunksById = new HashMap<>();
        Map<Long, RankedCandidate> candidates = new LinkedHashMap<>();
        for (int index = 0; index < keywordChunks.size(); index++) {
            CodeChunk chunk = keywordChunks.get(index);
            chunksById.put(chunk.getId(), chunk);
            candidates.computeIfAbsent(chunk.getId(), RankedCandidate::new).setKeywordRank(index + 1);
        }
        for (int index = 0; index < vectorMatches.size(); index++) {
            VectorMatch match = vectorMatches.get(index);
            candidates.computeIfAbsent(match.chunkId(), RankedCandidate::new)
                    .setVectorRank(index + 1, match.similarity());
        }

        List<Long> missingChunkIds = candidates.keySet().stream()
                .filter(id -> !chunksById.containsKey(id))
                .toList();
        if (!missingChunkIds.isEmpty()) {
            codeChunkRepository.findDetailedByIdIn(missingChunkIds)
                    .forEach(chunk -> chunksById.put(chunk.getId(), chunk));
        }

        boolean hybridRanking = !vectorMatches.isEmpty();
        List<RankedCandidate> ranked = new ArrayList<>(candidates.values());
        ranked.forEach(candidate -> candidate.calculateScore(hybridRanking));
        ranked.sort(Comparator
                .comparingDouble(RankedCandidate::combinedScore).reversed()
                .thenComparingInt(RankedCandidate::keywordRankForSort)
                .thenComparingInt(RankedCandidate::vectorRankForSort)
                .thenComparing(RankedCandidate::chunkId));

        return ranked.stream()
                .limit(limit)
                .map(candidate -> CodeSearchResultResponse.from(
                        chunksById.get(candidate.chunkId()),
                        candidate.matchType(),
                        round(candidate.keywordScore()),
                        round(candidate.vectorSimilarity()),
                        round(candidate.combinedScore())
                ))
                .toList();
    }

    private CodeSearchResponse response(
            String query,
            int limit,
            String retrievalMode,
            String embeddingStatus,
            String provider,
            String model,
            List<CodeSearchResultResponse> results
    ) {
        return new CodeSearchResponse(
                query,
                limit,
                retrievalMode,
                retrievalModeLabel(retrievalMode),
                embeddingStatus,
                provider,
                model,
                results
        );
    }

    private String retrievalMode(List<CodeChunk> keywordChunks, SemanticSearchResult semantic) {
        if (!semantic.matches().isEmpty()) {
            return keywordChunks.isEmpty() ? "VECTOR" : "HYBRID";
        }
        return semantic.status().equals("DISABLED") ? "KEYWORD" : "KEYWORD_FALLBACK";
    }

    private String retrievalModeLabel(String retrievalMode) {
        return switch (retrievalMode) {
            case "HYBRID" -> "关键词 + 向量混合检索";
            case "VECTOR" -> "向量语义检索";
            case "KEYWORD_FALLBACK" -> "关键词降级检索";
            default -> "关键词检索";
        };
    }

    private Double round(Double value) {
        if (value == null) {
            return null;
        }
        return Math.round(value * 1_000_000d) / 1_000_000d;
    }

    private String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private final class RankedCandidate {

        private final Long chunkId;
        private Integer keywordRank;
        private Integer vectorRank;
        private Double vectorSimilarity;
        private Double keywordScore;
        private double combinedScore;

        private RankedCandidate(Long chunkId) {
            this.chunkId = chunkId;
        }

        private void setKeywordRank(int rank) {
            this.keywordRank = rank;
        }

        private void setVectorRank(int rank, double similarity) {
            this.vectorRank = rank;
            this.vectorSimilarity = Math.max(-1.0, Math.min(1.0, similarity));
        }

        private void calculateScore(boolean hybridRanking) {
            keywordScore = keywordRank == null ? null : reciprocalRank(keywordRank);
            double vectorRankScore = vectorRank == null ? 0 : reciprocalRank(vectorRank);
            if (!hybridRanking) {
                combinedScore = keywordScore == null ? 0 : keywordScore;
                return;
            }
            combinedScore = embeddingConfiguration.keywordWeight() * (keywordScore == null ? 0 : keywordScore)
                    + embeddingConfiguration.vectorWeight() * vectorRankScore;
        }

        private double reciprocalRank(int rank) {
            return (RRF_CONSTANT + 1.0) / (RRF_CONSTANT + rank);
        }

        private Long chunkId() {
            return chunkId;
        }

        private Double keywordScore() {
            return keywordScore;
        }

        private Double vectorSimilarity() {
            return vectorSimilarity;
        }

        private double combinedScore() {
            return combinedScore;
        }

        private int keywordRankForSort() {
            return keywordRank == null ? Integer.MAX_VALUE : keywordRank;
        }

        private int vectorRankForSort() {
            return vectorRank == null ? Integer.MAX_VALUE : vectorRank;
        }

        private String matchType() {
            if (keywordRank != null && vectorRank != null) {
                return "HYBRID";
            }
            return vectorRank != null ? "VECTOR" : "KEYWORD";
        }
    }
}
