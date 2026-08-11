package com.repopilot.toolserver.common;

import com.repopilot.toolserver.tool.ToolNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ToolNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiResponse<Void> toolNotFound(ToolNotFoundException exception) {
        return ApiResponse.fail("MCP_TOOL_NOT_FOUND", exception.getMessage());
    }
}
