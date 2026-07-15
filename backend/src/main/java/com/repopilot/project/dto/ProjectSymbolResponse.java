package com.repopilot.project.dto;

import com.repopilot.indexer.domain.CodeSymbol;
import com.repopilot.indexer.domain.CodeSymbolType;

public record ProjectSymbolResponse(
        Long id,
        String filePath,
        CodeSymbolType symbolType,
        String name,
        String qualifiedName,
        String annotations,
        Integer startLine,
        Integer endLine
) {

    public static ProjectSymbolResponse from(CodeSymbol symbol) {
        return new ProjectSymbolResponse(
                symbol.getId(),
                symbol.getCodeFile().getPath(),
                symbol.getSymbolType(),
                symbol.getName(),
                symbol.getQualifiedName(),
                symbol.getAnnotations(),
                symbol.getStartLine(),
                symbol.getEndLine()
        );
    }
}
