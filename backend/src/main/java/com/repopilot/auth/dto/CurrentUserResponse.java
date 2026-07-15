package com.repopilot.auth.dto;

import com.repopilot.auth.security.UserPrincipal;
import com.repopilot.user.domain.User;

public record CurrentUserResponse(
        Long id,
        String email,
        String displayName,
        String role
) {

    public static CurrentUserResponse from(User user) {
        return new CurrentUserResponse(user.getId(), user.getEmail(), user.getDisplayName(), user.getRole());
    }

    public static CurrentUserResponse from(UserPrincipal principal) {
        return new CurrentUserResponse(principal.getId(), principal.getUsername(), principal.getDisplayName(), "USER");
    }
}

