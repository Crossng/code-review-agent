package com.repopilot.indexer.domain;

import java.time.Instant;

import com.repopilot.project.domain.Project;
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
@Table(name = "code_chunk")
public class CodeChunk {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id")
    private Project project;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "code_file_id")
    private CodeFile codeFile;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "symbol_id")
    private CodeSymbol symbol;

    @Enumerated(EnumType.STRING)
    @Column(name = "chunk_type", nullable = false)
    private CodeChunkType chunkType;

    @Column(nullable = false, columnDefinition = "text")
    private String content;

    @Column(columnDefinition = "text")
    private String summary;

    @Column(name = "start_line")
    private Integer startLine;

    @Column(name = "end_line")
    private Integer endLine;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected CodeChunk() {
    }

    public CodeChunk(
            Project project,
            CodeFile codeFile,
            CodeSymbol symbol,
            CodeChunkType chunkType,
            String content,
            String summary,
            Integer startLine,
            Integer endLine
    ) {
        this.project = project;
        this.codeFile = codeFile;
        this.symbol = symbol;
        this.chunkType = chunkType;
        this.content = content;
        this.summary = summary;
        this.startLine = startLine;
        this.endLine = endLine;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public CodeFile getCodeFile() {
        return codeFile;
    }

    public CodeSymbol getSymbol() {
        return symbol;
    }

    public CodeChunkType getChunkType() {
        return chunkType;
    }

    public String getContent() {
        return content;
    }

    public String getSummary() {
        return summary;
    }

    public Integer getStartLine() {
        return startLine;
    }

    public Integer getEndLine() {
        return endLine;
    }
}

