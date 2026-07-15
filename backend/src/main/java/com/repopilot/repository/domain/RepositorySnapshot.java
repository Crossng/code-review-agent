package com.repopilot.repository.domain;

import java.time.Instant;

import com.repopilot.project.domain.Project;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

@Entity
@Table(name = "repository_snapshot")
public class RepositorySnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id")
    private Project project;

    @Column(nullable = false)
    private String branch;

    @Column(name = "commit_sha", nullable = false)
    private String commitSha;

    @Column(name = "file_count", nullable = false)
    private int fileCount;

    @Column(name = "java_file_count", nullable = false)
    private int javaFileCount;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected RepositorySnapshot() {
    }

    public RepositorySnapshot(Project project, String branch, String commitSha, int fileCount, int javaFileCount) {
        this.project = project;
        this.branch = branch;
        this.commitSha = commitSha;
        this.fileCount = fileCount;
        this.javaFileCount = javaFileCount;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Project getProject() {
        return project;
    }

    public String getBranch() {
        return branch;
    }

    public String getCommitSha() {
        return commitSha;
    }

    public int getFileCount() {
        return fileCount;
    }

    public int getJavaFileCount() {
        return javaFileCount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}

