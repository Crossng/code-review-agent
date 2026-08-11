package com.repopilot.toolserver.tool;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class McpToolControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void listToolsReturnsChineseCatalogAndProtocolVersion() throws Exception {
        mockMvc.perform(get("/api/mcp/tools"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.service").value("RepoPilot MCP 工具目录服务"))
                .andExpect(jsonPath("$.data.protocolVersion").value(McpToolRegistryService.PROTOCOL_VERSION))
                .andExpect(jsonPath("$.data.toolCount").value(greaterThanOrEqualTo(16)))
                .andExpect(jsonPath("$.data.tools[0].name").value("list_project_files"));
    }

    @Test
    void getReadFileToolShowsSafetyRules() throws Exception {
        mockMvc.perform(get("/api/mcp/tools/read_file"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.title").value("读取项目文件"))
                .andExpect(jsonPath("$.data.accessMode").value("READ"))
                .andExpect(jsonPath("$.data.safetyRules[1]").value(containsString(".git")));
    }

    @Test
    void validateReadFileRejectsGitMetadataPath() throws Exception {
        mockMvc.perform(post("/api/mcp/tools/read_file/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "arguments": {
                                    "runId": 7,
                                    "path": ".git/config"
                                  }
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.valid").value(false))
                .andExpect(jsonPath("$.data.status").value("BLOCKED"))
                .andExpect(jsonPath("$.data.messages[0]").value(containsString("路径参数越界")));
    }

    @Test
    void validateSearchCodeAcceptsBoundedLimit() throws Exception {
        mockMvc.perform(post("/api/mcp/tools/search_code/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "arguments": {
                                    "runId": 7,
                                    "query": "User Controller",
                                    "limit": 8
                                  }
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.valid").value(true))
                .andExpect(jsonPath("$.data.status").value("READY"));
    }

    @Test
    void validateSearchCodeRejectsUnknownArgumentAndInvalidLimit() throws Exception {
        mockMvc.perform(post("/api/mcp/tools/search_code/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "arguments": {
                                    "runId": "7",
                                    "query": "User Controller",
                                    "limit": 21,
                                    "unexpected": true
                                  }
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.valid").value(false))
                .andExpect(jsonPath("$.data.status").value("BLOCKED"))
                .andExpect(jsonPath("$.data.messages[0]").value("参数 runId 必须是 number。"))
                .andExpect(jsonPath("$.data.messages[1]").value("未知参数：unexpected"))
                .andExpect(jsonPath("$.data.messages[2]").value("limit 必须是 1 到 20 之间的整数。"));
    }

    @Test
    void unknownToolReturnsChineseError() throws Exception {
        mockMvc.perform(get("/api/mcp/tools/not_exists"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("MCP_TOOL_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("没有找到 MCP 工具：not_exists"));
    }
}
