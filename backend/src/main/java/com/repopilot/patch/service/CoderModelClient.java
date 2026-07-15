package com.repopilot.patch.service;

import java.util.List;
import java.util.Optional;

import com.repopilot.agent.domain.AgentTask;
import com.repopilot.indexer.dto.CodeSearchResultResponse;

public interface CoderModelClient {

    Optional<CoderModelResponse> generatePatch(CoderModelRequest request);

    record CoderModelRequest(
            Long taskId,
            String taskTitle,
            String taskDescription,
            String taskType,
            String projectRepo,
            String defaultBranch,
            List<CoderContextCandidate> retrievedContext
    ) {

        public static CoderModelRequest from(AgentTask task, List<CodeSearchResultResponse> retrievedResults) {
            return new CoderModelRequest(
                    task.getId(),
                    task.getTitle(),
                    task.getDescription(),
                    task.getTaskType().name(),
                    task.getProject().getRepoFullName(),
                    task.getProject().getDefaultBranch(),
                    retrievedResults.stream().limit(8).map(CoderContextCandidate::from).toList()
            );
        }
    }

    record CoderContextCandidate(
            Long chunkId,
            String filePath,
            String chunkType,
            String symbolType,
            String qualifiedName,
            Integer startLine,
            Integer endLine,
            String summary,
            String preview
    ) {

        static CoderContextCandidate from(CodeSearchResultResponse result) {
            return new CoderContextCandidate(
                    result.chunkId(),
                    result.filePath(),
                    result.chunkType() == null ? null : result.chunkType().name(),
                    result.symbolType() == null ? null : result.symbolType().name(),
                    result.qualifiedName(),
                    result.startLine(),
                    result.endLine(),
                    result.summary(),
                    result.preview()
            );
        }
    }

    record CoderModelResponse(
            String provider,
            String model,
            String rawOutput
    ) {
    }
}
