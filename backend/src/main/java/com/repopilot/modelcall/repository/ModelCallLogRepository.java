package com.repopilot.modelcall.repository;

import java.util.List;

import com.repopilot.modelcall.domain.ModelCallLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ModelCallLogRepository extends JpaRepository<ModelCallLog, Long> {

    List<ModelCallLog> findByAgentRunIdOrderByStartedAtAsc(Long agentRunId);
}
