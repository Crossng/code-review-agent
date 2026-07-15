package com.repopilot.project.repository;

import java.util.List;
import java.util.Optional;

import com.repopilot.project.domain.ProjectControllerApiDocSnapshot;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface ProjectControllerApiDocSnapshotRepository extends JpaRepository<ProjectControllerApiDocSnapshot, Long> {

    List<ProjectControllerApiDocSnapshot> findByProjectIdOrderByGeneratedAtDesc(Long projectId, Pageable pageable);

    Optional<ProjectControllerApiDocSnapshot> findByIdAndProjectId(Long id, Long projectId);

    @Modifying
    @Query("delete from ProjectControllerApiDocSnapshot snapshot where snapshot.project.id = :projectId")
    int deleteByProjectId(Long projectId);
}
