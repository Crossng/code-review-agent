package com.repopilot.indexer.domain;

import java.time.Instant;

import com.repopilot.project.domain.Project;
import com.repopilot.repository.domain.RepositorySnapshot;
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
@Table(name = "code_file")
public class CodeFile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id")
    private Project project;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "snapshot_id")
    private RepositorySnapshot snapshot;

    @Column(nullable = false, columnDefinition = "text")
    private String path;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CodeFileLanguage language;

    @Column(nullable = false)
    private String sha256;

    @Column(name = "size_bytes", nullable = false)
    private int sizeBytes;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected CodeFile() {
    }

    public CodeFile(Project project, RepositorySnapshot snapshot, String path, CodeFileLanguage language, String sha256, int sizeBytes) {
        this.project = project;
        this.snapshot = snapshot;
        this.path = path;
        this.language = language;
        this.sha256 = sha256;
        this.sizeBytes = sizeBytes;
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

    public String getPath() {
        return path;
    }

    public CodeFileLanguage getLanguage() {
        return language;
    }

    public String getSha256() {
        return sha256;
    }

    public int getSizeBytes() {
        return sizeBytes;
    }
}

