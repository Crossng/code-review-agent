package com.repopilot.project.repository;

import java.util.List;
import java.util.Optional;

import com.repopilot.project.domain.Project;
import com.repopilot.project.domain.ProjectStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface ProjectRepository extends JpaRepository<Project, Long> {

    List<Project> findByOwnerIdOrderByCreatedAtDesc(Long ownerId);

    @Query("""
            select project from Project project
            where project.owner.id = :ownerId
              and (:status is null or project.status = :status)
              and (
                  :queryPresent = false
                  or lower(project.repoFullName) like :queryPattern
                  or lower(project.repoUrl) like :queryPattern
              )
            order by project.createdAt desc
            """)
    List<Project> search(
            Long ownerId,
            ProjectStatus status,
            boolean queryPresent,
            String queryPattern
    );

    long countByOwnerId(Long ownerId);

    long countByOwnerIdAndStatus(Long ownerId, ProjectStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select project from Project project where project.id = :id")
    Optional<Project> findByIdForUpdate(Long id);
}
