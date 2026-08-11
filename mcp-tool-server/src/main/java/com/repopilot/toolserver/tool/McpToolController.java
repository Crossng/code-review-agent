package com.repopilot.toolserver.tool;

import java.util.Map;

import com.repopilot.toolserver.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/mcp/tools")
public class McpToolController {

    private final McpToolRegistryService toolRegistryService;

    public McpToolController(McpToolRegistryService toolRegistryService) {
        this.toolRegistryService = toolRegistryService;
    }

    @GetMapping
    public ApiResponse<McpToolListResponse> listTools() {
        var tools = toolRegistryService.listTools();
        return ApiResponse.ok(new McpToolListResponse(
                "RepoPilot MCP 工具目录服务",
                McpToolRegistryService.PROTOCOL_VERSION,
                tools.size(),
                tools
        ));
    }

    @GetMapping("/{toolName}")
    public ApiResponse<McpToolDefinition> getTool(@PathVariable String toolName) {
        return ApiResponse.ok(toolRegistryService.getTool(toolName));
    }

    @PostMapping("/{toolName}/validate")
    public ApiResponse<ToolValidationResponse> validate(
            @PathVariable String toolName,
            @RequestBody(required = false) ToolValidationRequest request
    ) {
        Map<String, Object> arguments = request == null ? Map.of() : request.arguments();
        return ApiResponse.ok(toolRegistryService.validate(toolName, arguments));
    }
}
