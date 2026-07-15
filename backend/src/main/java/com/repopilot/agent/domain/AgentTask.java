package com.repopilot.agent.domain;

import java.time.Instant;

import com.repopilot.project.domain.Project;
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
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

@Entity
@Table(name = "agent_task")
public class AgentTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id")
    private Project project;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "task_type", nullable = false)
    private AgentTaskType taskType;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false, columnDefinition = "text")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AgentTaskStatus status;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "current_run_id")
    private AgentRun currentRun;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AgentTask() {
    }

    public AgentTask(Project project, User user, AgentTaskType taskType, String title, String description) {
        this.project = project;
        this.user = user;
        this.taskType = taskType;
        this.title = title;
        this.description = description;
        this.status = AgentTaskStatus.CREATED;
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

    public Project getProject() {
        return project;
    }

    public User getUser() {
        return user;
    }

    public AgentTaskType getTaskType() {
        return taskType;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public AgentTaskStatus getStatus() {
        return status;
    }

    public AgentRun getCurrentRun() {
        return currentRun;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setStatus(AgentTaskStatus status) {
        this.status = status;
    }

    public void setCurrentRun(AgentRun currentRun) {
        this.currentRun = currentRun;
    }
}

