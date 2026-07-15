package com.repopilot.agent.repository;

import java.util.List;
import java.util.Optional;

import com.repopilot.agent.domain.AgentRunReportSnapshot;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentRunReportSnapshotRepository extends JpaRepository<AgentRunReportSnapshot, Long> {

    List<AgentRunReportSnapshot> findByAgentTaskIdOrderByReportGeneratedAtDesc(Long agentTaskId, Pageable pageable);

    Optional<AgentRunReportSnapshot> findByIdAndAgentTaskId(Long id, Long agentTaskId);
}
