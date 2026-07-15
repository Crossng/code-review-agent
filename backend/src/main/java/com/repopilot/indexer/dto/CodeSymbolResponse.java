package com.repopilot.indexer.dto;

import com.repopilot.indexer.domain.CodeSymbol;
import com.repopilot.indexer.domain.CodeSymbolType;

public record CodeSymbolResponse(
        Long id,
        String filePath,
        CodeSymbolType symbolType,
        String name,
        String qualifiedName,
        String annotations,
        Integer startLine,
        Integer endLine
) {

    public static CodeSymbolResponse from(CodeSymbol symbol) {
        return new CodeSymbolResponse(
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

