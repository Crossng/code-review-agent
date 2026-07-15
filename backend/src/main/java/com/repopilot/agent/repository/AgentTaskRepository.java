package com.repopilot.agent.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import com.repopilot.agent.domain.AgentTask;
import com.repopilot.agent.domain.AgentTaskStatus;
import com.repopilot.agent.domain.AgentTaskType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface AgentTaskRepository extends JpaRepository<AgentTask, Long> {

    List<AgentTask> findByUserIdOrderByCreatedAtDesc(Long userId);

    @Query("""
            select task from AgentTask task
            where task.user.id = :userId
              and (:projectId is null or task.project.id = :projectId)
              and (:status is null or task.status = :status)
              and (:taskType is null or task.taskType = :taskType)
              and (
                  :queryPresent = false
                  or lower(task.title) like :queryPattern
                  or lower(task.description) like :queryPattern
              )
            order by task.createdAt desc
            """)
    List<AgentTask> search(
            Long userId,
            Long projectId,
            AgentTaskStatus status,
            AgentTaskType taskType,
            boolean queryPresent,
            String queryPattern
    );

    long countByUserId(Long userId);

    long countByUserIdAndStatus(Long userId, AgentTaskStatus status);

    long countByUserIdAndStatusIn(Long userId, Collection<AgentTaskStatus> statuses);

    @Query("select task.status from AgentTask task where task.id = :id")
    Optional<AgentTaskStatus> findStatusById(Long id);

    boolean existsByProjectIdAndStatusInAndIdNot(
            Long projectId,
            Collection<AgentTaskStatus> statuses,
            Long excludedTaskId
    );
}
