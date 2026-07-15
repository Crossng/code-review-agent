package com.repopilot.repository.repository;

import java.util.Optional;

import com.repopilot.repository.domain.RepositorySnapshot;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RepositorySnapshotRepository extends JpaRepository<RepositorySnapshot, Long> {

    Optional<RepositorySnapshot> findFirstByProjectIdOrderByCreatedAtDesc(Long projectId);
}

