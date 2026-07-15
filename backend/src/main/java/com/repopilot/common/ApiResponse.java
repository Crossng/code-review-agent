package com.repopilot.common;

import org.slf4j.MDC;

public record ApiResponse<T>(
        boolean success,
        T data,
        String code,
        String message,
        String traceId
) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, data, null, null, MDC.get("traceId"));
    }

    public static ApiResponse<Void> error(String code, String message) {
        return new ApiResponse<>(false, null, code, message, MDC.get("traceId"));
    }
}

