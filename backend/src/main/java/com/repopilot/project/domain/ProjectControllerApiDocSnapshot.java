package com.repopilot.project.domain;

import java.time.Instant;

import com.repopilot.project.dto.ProjectControllerApiDocsResponse;
import com.repopilot.user.domain.User;
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
@Table(name = "controller_api_doc_snapshot")
public class ProjectControllerApiDocSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id")
    private Project project;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "generated_by_user_id")
    private User generatedBy;

    @Column(name = "repo_full_name", nullable = false)
    private String repoFullName;

    @Column(name = "generated_at", nullable = false)
    private Instant generatedAt;

    @Column(name = "route_count", nullable = false)
    private int routeCount;

    @Column(name = "filtered_count", nullable = false)
    private long filteredCount;

    @Column(name = "risk_level")
    private String riskLevel;

    @Column(name = "risk_code")
    private String riskCode;

    @Column(nullable = false, columnDefinition = "text")
    private String markdown;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ProjectControllerApiDocSnapshot() {
    }

    public ProjectControllerApiDocSnapshot(Project project, User generatedBy, ProjectControllerApiDocsResponse docs) {
        this.project = project;
        this.generatedBy = generatedBy;
        this.repoFullName = docs.repoFullName();
        this.generatedAt = docs.generatedAt();
        this.routeCount = docs.routeCount();
        this.filteredCount = docs.filteredCount();
        this.riskLevel = docs.filters().riskLevel();
        this.riskCode = docs.filters().riskCode();
        this.markdown = docs.markdown();
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

    public User getGeneratedBy() {
        return generatedBy;
    }

    public String getRepoFullName() {
        return repoFullName;
    }

    public Instant getGeneratedAt() {
        return generatedAt;
    }

    public int getRouteCount() {
        return routeCount;
    }

    public long getFilteredCount() {
        return filteredCount;
    }

    public String getRiskLevel() {
        return riskLevel;
    }

    public String getRiskCode() {
        return riskCode;
    }

    public String getMarkdown() {
        return markdown;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
