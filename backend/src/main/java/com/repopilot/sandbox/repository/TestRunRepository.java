package com.repopilot.sandbox.repository;

import java.util.List;
import java.util.Optional;

import com.repopilot.sandbox.domain.TestRun;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TestRunRepository extends JpaRepository<TestRun, Long> {

    @EntityGraph(attributePaths = {"agentRun", "patch"})
    List<TestRun> findByAgentRunIdOrderByCreatedAtDesc(Long agentRunId);

    @EntityGraph(attributePaths = {"agentRun", "patch"})
    Optional<TestRun> findFirstByPatchIdOrderByCreatedAtDesc(Long patchId);
}
