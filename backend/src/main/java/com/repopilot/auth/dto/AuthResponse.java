package com.repopilot.auth.dto;

public record AuthResponse(
        String token,
        long expiresInMinutes,
        CurrentUserResponse user
) {
}

