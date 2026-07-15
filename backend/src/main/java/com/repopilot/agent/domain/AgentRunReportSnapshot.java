package com.repopilot.agent.domain;

import java.time.Instant;

import com.repopilot.agent.dto.AgentRunReportResponse;
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
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

@Entity
@Table(name = "agent_run_report_snapshot")
public class AgentRunReportSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "agent_task_id")
    private AgentTask agentTask;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "agent_run_id")
    private AgentRun agentRun;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id")
    private Project project;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "generated_by_user_id")
    private User generatedBy;

    @Column(name = "project_name", nullable = false)
    private String projectName;

    @Column(name = "task_title", nullable = false)
    private String taskTitle;

    @Enumerated(EnumType.STRING)
    @Column(name = "task_type", nullable = false)
    private AgentTaskType taskType;

    @Enumerated(EnumType.STRING)
    @Column(name = "task_status", nullable = false)
    private AgentTaskStatus taskStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "run_status", nullable = false)
    private AgentRunStatus runStatus;

    @Column(name = "run_started_at", nullable = false)
    private Instant runStartedAt;

    @Column(name = "run_finished_at")
    private Instant runFinishedAt;

    @Column(name = "report_generated_at", nullable = false)
    private Instant reportGeneratedAt;

    @Column(name = "section_count", nullable = false)
    private int sectionCount;

    @Column(nullable = false, columnDefinition = "text")
    private String markdown;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected AgentRunReportSnapshot() {
    }

    public AgentRunReportSnapshot(AgentTask task, User generatedBy, AgentRunReportResponse report) {
        this.agentTask = task;
        this.agentRun = task.getCurrentRun();
        this.project = task.getProject();
        this.generatedBy = generatedBy;
        this.projectName = report.projectName();
        this.taskTitle = report.taskTitle();
        this.taskType = report.taskType();
        this.taskStatus = report.taskStatus();
        this.runStatus = report.runStatus();
        this.runStartedAt = report.startedAt();
        this.runFinishedAt = report.finishedAt();
        this.reportGeneratedAt = report.generatedAt();
        this.sectionCount = report.sections().size();
        this.markdown = report.markdown();
    }

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public AgentTask getAgentTask() {
        return agentTask;
    }

    public AgentRun getAgentRun() {
        return agentRun;
    }

    public Project getProject() {
        return project;
    }

    public User getGeneratedBy() {
        return generatedBy;
    }

    public String getProjectName() {
        return projectName;
    }

    public String getTaskTitle() {
        return taskTitle;
    }

    public AgentTaskType getTaskType() {
        return taskType;
    }

    public AgentTaskStatus getTaskStatus() {
        return taskStatus;
    }

    public AgentRunStatus getRunStatus() {
        return runStatus;
    }

    public Instant getRunStartedAt() {
        return runStartedAt;
    }

    public Instant getRunFinishedAt() {
        return runFinishedAt;
    }

    public Instant getReportGeneratedAt() {
        return reportGeneratedAt;
    }

    public int getSectionCount() {
        return sectionCount;
    }

    public String getMarkdown() {
        return markdown;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
