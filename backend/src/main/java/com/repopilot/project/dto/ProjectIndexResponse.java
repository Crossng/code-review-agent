package com.repopilot.project.dto;

import java.time.Instant;

public record ProjectIndexResponse(
        Long projectId,
        Long snapshotId,
        long fileCount,
        long javaFileCount,
        long symbolCount,
        long chunkCount,
        Instant indexedAt,
        String message
) {
}
