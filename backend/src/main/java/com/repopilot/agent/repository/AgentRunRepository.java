package com.repopilot.agent.repository;

import java.time.Instant;
import java.util.List;

import com.repopilot.agent.domain.AgentRun;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface AgentRunRepository extends JpaRepository<AgentRun, Long> {

    @Query("""
            select run from AgentRun run
            join fetch run.agentTask task
            where task.user.id = :userId and run.startedAt >= :startedAt
            order by run.startedAt asc
            """)
    List<AgentRun> findDashboardRuns(Long userId, Instant startedAt);
}
