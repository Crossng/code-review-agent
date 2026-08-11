package com.repopilot.toolserver.common;

public record ApiResponse<T>(
        boolean success,
        T data,
        String code,
        String message
) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, data, null, null);
    }

    public static <T> ApiResponse<T> fail(String code, String message) {
        return new ApiResponse<>(false, null, code, message);
    }
}
