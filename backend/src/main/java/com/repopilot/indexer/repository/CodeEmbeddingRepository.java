package com.repopilot.indexer.repository;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class CodeEmbeddingRepository {

    private final JdbcTemplate jdbcTemplate;

    public CodeEmbeddingRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void replaceProjectEmbeddings(
            Long projectId,
            String provider,
            String model,
            List<ChunkEmbedding> embeddings
    ) {
        jdbcTemplate.update(
                "delete from code_embedding where project_id = ? and embedding_provider = ? and embedding_model = ?",
                projectId,
                provider,
                model
        );
        if (embeddings.isEmpty()) {
            return;
        }
        jdbcTemplate.batchUpdate(
                """
                        insert into code_embedding (
                            chunk_id,
                            project_id,
                            embedding_provider,
                            embedding_model,
                            embedding_dimension,
                            content_sha256,
                            embedding,
                            created_at
                        ) values (?, ?, ?, ?, ?, ?, cast(? as vector), ?)
                        """,
                embeddings,
                100,
                (PreparedStatement statement, ChunkEmbedding embedding) -> {
                    statement.setLong(1, embedding.chunkId());
                    statement.setLong(2, projectId);
                    statement.setString(3, provider);
                    statement.setString(4, model);
                    statement.setInt(5, embedding.vector().size());
                    statement.setString(6, embedding.contentSha256());
                    statement.setString(7, vectorLiteral(embedding.vector()));
                    statement.setTimestamp(8, Timestamp.from(Instant.now()));
                }
        );
    }

    public List<VectorMatch> findNearest(
            Long projectId,
            String provider,
            String model,
            List<Double> queryVector,
            int limit,
            double minimumSimilarity
    ) {
        String vector = vectorLiteral(queryVector);
        return jdbcTemplate.query(
                """
                        with query_vector as (
                            select cast(? as vector) as embedding
                        ), ranked as (
                            select
                                stored.chunk_id,
                                1 - (stored.embedding <=> query_vector.embedding) as similarity
                            from code_embedding stored
                            cross join query_vector
                            where stored.project_id = ?
                              and stored.embedding_provider = ?
                              and stored.embedding_model = ?
                              and stored.embedding_dimension = ?
                        )
                        select chunk_id, similarity
                        from ranked
                        where similarity >= ?
                        order by similarity desc, chunk_id asc
                        limit ?
                        """,
                (resultSet, rowNumber) -> new VectorMatch(
                        resultSet.getLong("chunk_id"),
                        resultSet.getDouble("similarity")
                ),
                vector,
                projectId,
                provider,
                model,
                queryVector.size(),
                minimumSimilarity,
                limit
        );
    }

    public long countByProjectId(Long projectId) {
        Long count = jdbcTemplate.queryForObject(
                "select count(*) from code_embedding where project_id = ?",
                Long.class,
                projectId
        );
        return count == null ? 0 : count;
    }

    private String vectorLiteral(List<Double> vector) {
        if (vector == null || vector.isEmpty()) {
            throw new IllegalArgumentException("Vector must not be empty");
        }
        StringBuilder literal = new StringBuilder("[");
        for (int index = 0; index < vector.size(); index++) {
            double value = vector.get(index);
            if (!Double.isFinite(value)) {
                throw new IllegalArgumentException("Vector contains a non-finite value");
            }
            if (index > 0) {
                literal.append(',');
            }
            literal.append(Double.toString(value));
        }
        return literal.append(']').toString();
    }

    public record ChunkEmbedding(Long chunkId, String contentSha256, List<Double> vector) {
    }

    public record VectorMatch(Long chunkId, double similarity) {
    }
}
