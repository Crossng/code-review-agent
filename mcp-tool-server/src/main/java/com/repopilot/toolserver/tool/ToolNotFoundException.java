package com.repopilot.toolserver.tool;

public class ToolNotFoundException extends RuntimeException {

    public ToolNotFoundException(String toolName) {
        super("没有找到 MCP 工具：" + toolName);
    }
}
