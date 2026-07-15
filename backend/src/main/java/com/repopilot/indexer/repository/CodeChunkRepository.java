package com.repopilot.indexer.repository;

import java.util.List;

import com.repopilot.indexer.domain.CodeChunk;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CodeChunkRepository extends JpaRepository<CodeChunk, Long> {

    void deleteByProjectId(Long projectId);

    long countByProjectId(Long projectId);

    @EntityGraph(attributePaths = {"codeFile", "symbol"})
    @Query("""
            select chunk
            from CodeChunk chunk
            join chunk.codeFile file
            left join chunk.symbol symbol
            where chunk.project.id = :projectId
              and (
                lower(chunk.content) like lower(concat('%', :query, '%'))
                or lower(chunk.summary) like lower(concat('%', :query, '%'))
                or lower(file.path) like lower(concat('%', :query, '%'))
                or lower(symbol.name) like lower(concat('%', :query, '%'))
                or lower(symbol.qualifiedName) like lower(concat('%', :query, '%'))
              )
            order by
              case when lower(symbol.name) = lower(:query) then 0 else 1 end,
              chunk.id asc
            """)
    List<CodeChunk> search(@Param("projectId") Long projectId, @Param("query") String query, Pageable pageable);
}

