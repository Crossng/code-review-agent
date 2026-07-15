package com.repopilot.patch.repository;

import java.util.List;
import java.util.Optional;

import com.repopilot.patch.domain.PatchRecord;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PatchRecordRepository extends JpaRepository<PatchRecord, Long> {

    @EntityGraph(attributePaths = {"agentTask", "agentRun"})
    List<PatchRecord> findByAgentTaskIdOrderByCreatedAtDesc(Long agentTaskId);

    @EntityGraph(attributePaths = {"agentTask", "agentRun"})
    Optional<PatchRecord> findFirstByAgentTaskIdOrderByCreatedAtDesc(Long agentTaskId);
}

