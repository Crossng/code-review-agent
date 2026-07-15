package com.repopilot.agent.worker;

import java.util.List;

import com.repopilot.common.ApiResponse;
import com.repopilot.indexer.dto.CodeSearchResponse;
import com.repopilot.project.dto.ProjectFileResponse;
import com.repopilot.project.dto.ProjectSymbolResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AgentWorkerToolController {

    private final AgentWorkerToolService toolService;

    public AgentWorkerToolController(AgentWorkerToolService toolService) {
        this.toolService = toolService;
    }

    @GetMapping("/api/internal/agent-worker/runs/{runId}/context")
    public ApiResponse<AgentWorkerRunContextResponse> loadRunContext(
            @PathVariable Long runId,
            @RequestHeader(name = AgentWorkerCallbackController.CALLBACK_TOKEN_HEADER, required = false) String callbackToken
    ) {
        return ApiResponse.ok(toolService.loadRunContext(runId, callbackToken));
    }

    @GetMapping("/api/internal/agent-worker/runs/{runId}/project/files")
    public ApiResponse<List<ProjectFileResponse>> listProjectFiles(
            @PathVariable Long runId,
            @RequestHeader(name = AgentWorkerCallbackController.CALLBACK_TOKEN_HEADER, required = false) String callbackToken,
            @RequestParam(defaultValue = "6") int maxDepth
    ) {
        return ApiResponse.ok(toolService.listProjectFiles(runId, callbackToken, maxDepth));
    }

    @GetMapping("/api/internal/agent-worker/runs/{runId}/project/file")
    public ApiResponse<AgentWorkerFileContentResponse> readProjectFile(
            @PathVariable Long runId,
            @RequestHeader(name = AgentWorkerCallbackController.CALLBACK_TOKEN_HEADER, required = false) String callbackToken,
            @RequestParam String path
    ) {
        return ApiResponse.ok(toolService.readProjectFile(runId, callbackToken, path));
    }

    @GetMapping("/api/internal/agent-worker/runs/{runId}/project/search")
    public ApiResponse<CodeSearchResponse> searchCode(
            @PathVariable Long runId,
            @RequestHeader(name = AgentWorkerCallbackController.CALLBACK_TOKEN_HEADER, required = false) String callbackToken,
            @RequestParam String query,
            @RequestParam(defaultValue = "8") int limit
    ) {
        return ApiResponse.ok(toolService.searchCode(runId, callbackToken, query, limit));
    }

    @GetMapping("/api/internal/agent-worker/runs/{runId}/project/symbols")
    public ApiResponse<List<ProjectSymbolResponse>> listSymbols(
            @PathVariable Long runId,
            @RequestHeader(name = AgentWorkerCallbackController.CALLBACK_TOKEN_HEADER, required = false) String callbackToken,
            @RequestParam(required = false) String type
    ) {
        return ApiResponse.ok(toolService.listSymbols(runId, callbackToken, type));
    }
}
