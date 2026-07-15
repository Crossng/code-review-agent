package com.repopilot.indexer.repository;

import java.util.List;

import com.repopilot.indexer.domain.CodeFile;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CodeFileRepository extends JpaRepository<CodeFile, Long> {

    void deleteByProjectId(Long projectId);

    List<CodeFile> findByProjectIdOrderByPathAsc(Long projectId);
}

