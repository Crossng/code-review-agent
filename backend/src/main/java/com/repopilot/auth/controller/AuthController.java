package com.repopilot.auth.controller;

import com.repopilot.auth.dto.AuthResponse;
import com.repopilot.auth.dto.CurrentUserResponse;
import com.repopilot.auth.dto.LoginRequest;
import com.repopilot.auth.dto.RegisterRequest;
import com.repopilot.auth.security.UserPrincipal;
import com.repopilot.auth.service.AuthService;
import com.repopilot.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    public ApiResponse<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ApiResponse.ok(authService.register(request));
    }

    @PostMapping("/login")
    public ApiResponse<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        return ApiResponse.ok(authService.login(request));
    }

    @GetMapping("/me")
    public ApiResponse<CurrentUserResponse> me(@AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.ok(CurrentUserResponse.from(principal));
    }
}

