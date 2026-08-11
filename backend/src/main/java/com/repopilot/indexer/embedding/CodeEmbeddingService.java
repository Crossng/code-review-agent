package com.repopilot.indexer.embedding;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Supplier;

import com.repopilot.indexer.domain.CodeChunk;
import com.repopilot.indexer.domain.CodeSymbol;
import com.repopilot.indexer.repository.CodeEmbeddingRepository;
import com.repopilot.indexer.repository.CodeEmbeddingRepository.ChunkEmbedding;
import com.repopilot.indexer.repository.CodeEmbeddingRepository.VectorMatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class CodeEmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(CodeEmbeddingService.class);

    private final EmbeddingConfiguration configuration;
    private final EmbeddingModelClient modelClient;
    private final CodeEmbeddingRepository embeddingRepository;

    public CodeEmbeddingService(
            EmbeddingConfiguration configuration,
            EmbeddingModelClient modelClient,
            CodeEmbeddingRepository embeddingRepository
    ) {
        this.configuration = configuration;
        this.modelClient = modelClient;
        this.embeddingRepository = embeddingRepository;
    }

    public IndexingResult indexProject(Long projectId, List<CodeChunk> chunks) {
        return indexProject(projectId, () -> chunks);
    }

    public IndexingResult indexProject(Long projectId, Supplier<List<CodeChunk>> chunkSupplier) {
        if (!configuration.enabled()) {
            return IndexingResult.skipped("DISABLED", configuration);
        }
        if (!configuration.embeddingReady()) {
            return IndexingResult.skipped("NOT_READY", configuration);
        }
        List<CodeChunk> chunks = chunkSupplier.get();
        if (chunks.isEmpty()) {
            return new IndexingResult("INDEXED", 0, configuration.provider(), configuration.model(), null);
        }

        try {
            List<ChunkEmbedding> embeddings = new ArrayList<>(chunks.size());
            Integer dimension = null;
            for (int from = 0; from < chunks.size(); from += configuration.batchSize()) {
                int to = Math.min(chunks.size(), from + configuration.batchSize());
                List<CodeChunk> batch = chunks.subList(from, to);
                List<String> inputs = batch.stream().map(this::embeddingInput).toList();
                EmbeddingModelClient.EmbeddingBatch result = modelClient.embed(inputs);
                if (result.vectors().size() != batch.size()) {
                    throw new EmbeddingModelException("Embedding batch size does not match chunk batch size");
                }
                if (dimension != null && dimension != result.dimension()) {
                    throw new EmbeddingModelException("Embedding dimensions changed between batches");
                }
                dimension = result.dimension();
                for (int index = 0; index < batch.size(); index++) {
                    embeddings.add(new ChunkEmbedding(
                            batch.get(index).getId(),
                            sha256(inputs.get(index)),
                            result.vectors().get(index)
                    ));
                }
            }
            embeddingRepository.replaceProjectEmbeddings(
                    projectId,
                    configuration.provider(),
                    configuration.model(),
                    embeddings
            );
            return new IndexingResult(
                    "INDEXED",
                    embeddings.size(),
                    configuration.provider(),
                    configuration.model(),
                    dimension
            );
        } catch (EmbeddingModelException exception) {
            log.warn("Code embedding generation failed for project {}. Keyword search remains available: {}",
                    projectId, exception.getMessage());
            return IndexingResult.skipped("FALLBACK", configuration);
        }
    }

    public SemanticSearchResult search(Long projectId, String query, int limit) {
        if (!configuration.enabled()) {
            return SemanticSearchResult.unavailable("DISABLED", configuration);
        }
        if (!configuration.embeddingReady()) {
            return SemanticSearchResult.unavailable("NOT_READY", configuration);
        }
        try {
            EmbeddingModelClient.EmbeddingBatch result = modelClient.embed(List.of(query));
            List<VectorMatch> matches = embeddingRepository.findNearest(
                    projectId,
                    configuration.provider(),
                    configuration.model(),
                    result.vectors().get(0),
                    limit,
                    configuration.minimumSimilarity()
            );
            return new SemanticSearchResult(
                    matches.isEmpty() ? "NO_EMBEDDINGS" : "READY",
                    true,
                    configuration.provider(),
                    configuration.model(),
                    matches
            );
        } catch (EmbeddingModelException exception) {
            log.warn("Semantic code search failed for project {}. Falling back to keyword search: {}",
                    projectId, exception.getMessage());
            return SemanticSearchResult.unavailable("FAILED", configuration);
        }
    }

    private String embeddingInput(CodeChunk chunk) {
        CodeSymbol symbol = chunk.getSymbol();
        StringBuilder input = new StringBuilder();
        input.append("PATH ").append(chunk.getCodeFile().getPath()).append('\n');
        input.append("CHUNK_TYPE ").append(chunk.getChunkType()).append('\n');
        if (symbol != null) {
            input.append("SYMBOL_TYPE ").append(symbol.getSymbolType()).append('\n');
            input.append("SYMBOL ").append(symbol.getQualifiedName()).append('\n');
        }
        if (chunk.getSummary() != null && !chunk.getSummary().isBlank()) {
            input.append("SUMMARY ").append(chunk.getSummary()).append('\n');
        }
        input.append("CONTENT\n").append(chunk.getContent());
        if (input.length() > configuration.maxInputChars()) {
            return input.substring(0, configuration.maxInputChars());
        }
        return input.toString();
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    public record IndexingResult(
            String status,
            long embeddingCount,
            String provider,
            String model,
            Integer dimension
    ) {
        static IndexingResult skipped(String status, EmbeddingConfiguration configuration) {
            return new IndexingResult(status, 0, configuration.provider(), emptyToNull(configuration.model()), null);
        }
    }

    public record SemanticSearchResult(
            String status,
            boolean attempted,
            String provider,
            String model,
            List<VectorMatch> matches
    ) {
        static SemanticSearchResult unavailable(String status, EmbeddingConfiguration configuration) {
            return new SemanticSearchResult(
                    status,
                    configuration.enabled(),
                    configuration.provider(),
                    emptyToNull(configuration.model()),
                    List.of()
            );
        }
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
