package com.repopilot.indexer.domain;

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
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "code_symbol")
public class CodeSymbol {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id")
    private Project project;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "code_file_id")
    private CodeFile codeFile;

    @Enumerated(EnumType.STRING)
    @Column(name = "symbol_type", nullable = false)
    private CodeSymbolType symbolType;

    @Column(nullable = false)
    private String name;

    @Column(name = "qualified_name", nullable = false, columnDefinition = "text")
    private String qualifiedName;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String annotations;

    @Column(name = "start_line")
    private Integer startLine;

    @Column(name = "end_line")
    private Integer endLine;

    protected CodeSymbol() {
    }

    public CodeSymbol(
            Project project,
            CodeFile codeFile,
            CodeSymbolType symbolType,
            String name,
            String qualifiedName,
            String annotations,
            Integer startLine,
            Integer endLine
    ) {
        this.project = project;
        this.codeFile = codeFile;
        this.symbolType = symbolType;
        this.name = name;
        this.qualifiedName = qualifiedName;
        this.annotations = annotations;
        this.startLine = startLine;
        this.endLine = endLine;
    }

    public Long getId() {
        return id;
    }

    public CodeFile getCodeFile() {
        return codeFile;
    }

    public CodeSymbolType getSymbolType() {
        return symbolType;
    }

    public String getName() {
        return name;
    }

    public String getQualifiedName() {
        return qualifiedName;
    }

    public String getAnnotations() {
        return annotations;
    }

    public Integer getStartLine() {
        return startLine;
    }

    public Integer getEndLine() {
        return endLine;
    }
}

