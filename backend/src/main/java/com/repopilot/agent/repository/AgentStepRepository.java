package com.repopilot.agent.repository;

import java.util.List;

import com.repopilot.agent.domain.AgentStep;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface AgentStepRepository extends JpaRepository<AgentStep, Long> {

    List<AgentStep> findByAgentRunIdOrderByStartedAtAsc(Long agentRunId);

    @Query("""
            select step from AgentStep step
            join fetch step.agentRun run
            join fetch run.agentTask task
            join fetch task.project project
            where task.user.id = :userId
            order by coalesce(step.finishedAt, step.startedAt) desc, step.id desc
            """)
    List<AgentStep> findDashboardActivity(Long userId, Pageable pageable);
}
