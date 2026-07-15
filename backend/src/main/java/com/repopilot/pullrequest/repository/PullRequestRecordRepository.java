package com.repopilot.pullrequest.repository;

import java.util.Optional;

import com.repopilot.pullrequest.domain.PullRequestRecord;
import com.repopilot.pullrequest.domain.PullRequestStatus;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface PullRequestRecordRepository extends JpaRepository<PullRequestRecord, Long> {

    @EntityGraph(attributePaths = {"agentTask", "patch"})
    Optional<PullRequestRecord> findFirstByAgentTaskIdOrderByCreatedAtDesc(Long agentTaskId);

    @EntityGraph(attributePaths = {"agentTask", "patch"})
    Optional<PullRequestRecord> findFirstByPatchIdOrderByCreatedAtDesc(Long patchId);

    @Query("select count(record) from PullRequestRecord record where record.agentTask.user.id = :userId")
    long countByUserId(Long userId);

    @Query("select count(record) from PullRequestRecord record where record.agentTask.user.id = :userId and record.status = :status")
    long countByUserIdAndStatus(Long userId, PullRequestStatus status);
}
