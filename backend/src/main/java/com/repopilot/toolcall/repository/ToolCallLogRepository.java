package com.repopilot.toolcall.repository;

import java.util.List;

import com.repopilot.toolcall.domain.ToolCallLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ToolCallLogRepository extends JpaRepository<ToolCallLog, Long> {

    List<ToolCallLog> findByAgentRunIdOrderByStartedAtAsc(Long agentRunId);
}
