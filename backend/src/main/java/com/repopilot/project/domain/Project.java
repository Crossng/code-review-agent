package com.repopilot.project.domain;

import java.time.Instant;

import com.repopilot.user.domain.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

@Entity
@Table(name = "project")
public class Project {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_user_id")
    private User owner;

    @Column(name = "repo_url", nullable = false, columnDefinition = "text")
    private String repoUrl;

    @Column(name = "repo_full_name", nullable = false)
    private String repoFullName;

    @Column(name = "default_branch", nullable = false)
    private String defaultBranch;

    @Column(name = "local_path", columnDefinition = "text")
    private String localPath;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ProjectStatus status;

    @Column(name = "last_indexed_at")
    private Instant lastIndexedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Project() {
    }

    public Project(User owner, String repoUrl, String repoFullName, String defaultBranch) {
        this.owner = owner;
        this.repoUrl = repoUrl;
        this.repoFullName = repoFullName;
        this.defaultBranch = defaultBranch;
        this.status = ProjectStatus.CREATED;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public User getOwner() {
        return owner;
    }

    public String getRepoUrl() {
        return repoUrl;
    }

    public String getRepoFullName() {
        return repoFullName;
    }

    public String getDefaultBranch() {
        return defaultBranch;
    }

    public String getLocalPath() {
        return localPath;
    }

    public ProjectStatus getStatus() {
        return status;
    }

    public Instant getLastIndexedAt() {
        return lastIndexedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setLocalPath(String localPath) {
        this.localPath = localPath;
    }

    public void setStatus(ProjectStatus status) {
        this.status = status;
    }

    public void markIndexed(Instant indexedAt) {
        this.lastIndexedAt = indexedAt;
        this.status = ProjectStatus.READY;
    }
}

