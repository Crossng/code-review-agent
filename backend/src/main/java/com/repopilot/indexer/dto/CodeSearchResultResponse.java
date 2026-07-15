package com.repopilot.indexer.dto;

import com.repopilot.indexer.domain.CodeChunk;
import com.repopilot.indexer.domain.CodeChunkType;
import com.repopilot.indexer.domain.CodeSymbol;
import com.repopilot.indexer.domain.CodeSymbolType;

public record CodeSearchResultResponse(
        Long chunkId,
        String filePath,
        CodeChunkType chunkType,
        CodeSymbolType symbolType,
        String symbolName,
        String qualifiedName,
        Integer startLine,
        Integer endLine,
        String summary,
        String preview
) {

    public static CodeSearchResultResponse from(CodeChunk chunk) {
        CodeSymbol symbol = chunk.getSymbol();
        return new CodeSearchResultResponse(
                chunk.getId(),
                chunk.getCodeFile().getPath(),
                chunk.getChunkType(),
                symbol == null ? null : symbol.getSymbolType(),
                symbol == null ? null : symbol.getName(),
                symbol == null ? null : symbol.getQualifiedName(),
                chunk.getStartLine(),
                chunk.getEndLine(),
                chunk.getSummary(),
                preview(chunk.getContent())
        );
    }

    private static String preview(String content) {
        String compact = content.replaceAll("\\s+", " ").trim();
        if (compact.length() <= 240) {
            return compact;
        }
        return compact.substring(0, 240) + "...";
    }
}
