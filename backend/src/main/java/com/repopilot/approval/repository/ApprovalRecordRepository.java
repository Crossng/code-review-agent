package com.repopilot.approval.repository;

import java.util.List;

import com.repopilot.approval.domain.ApprovalRecord;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ApprovalRecordRepository extends JpaRepository<ApprovalRecord, Long> {

    @EntityGraph(attributePaths = {"agentTask", "patch", "user"})
    List<ApprovalRecord> findByAgentTaskIdOrderByCreatedAtDesc(Long agentTaskId);
}

