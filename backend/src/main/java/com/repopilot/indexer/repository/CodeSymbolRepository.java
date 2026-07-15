package com.repopilot.indexer.repository;

import java.util.List;

import com.repopilot.indexer.domain.CodeSymbol;
import com.repopilot.indexer.domain.CodeSymbolType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CodeSymbolRepository extends JpaRepository<CodeSymbol, Long> {

    void deleteByProjectId(Long projectId);

    @EntityGraph(attributePaths = "codeFile")
    List<CodeSymbol> findByProjectIdOrderByQualifiedNameAsc(Long projectId);

    @EntityGraph(attributePaths = "codeFile")
    List<CodeSymbol> findByProjectIdAndSymbolTypeOrderByQualifiedNameAsc(Long projectId, CodeSymbolType symbolType);
}
