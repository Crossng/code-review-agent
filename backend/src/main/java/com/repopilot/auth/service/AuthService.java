package com.repopilot.auth.service;

import com.repopilot.auth.dto.AuthResponse;
import com.repopilot.auth.dto.CurrentUserResponse;
import com.repopilot.auth.dto.LoginRequest;
import com.repopilot.auth.dto.RegisterRequest;
import com.repopilot.auth.security.JwtService;
import com.repopilot.common.ApiException;
import com.repopilot.user.domain.User;
import com.repopilot.user.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private static final long TOKEN_EXPIRATION_MINUTES = 720;

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new ApiException(HttpStatus.CONFLICT, "AUTH_EMAIL_EXISTS", "Email already registered");
        }
        User user = new User(
                request.email(),
                passwordEncoder.encode(request.password()),
                request.displayName(),
                "USER"
        );
        User saved = userRepository.save(user);
        return tokenResponse(saved);
    }

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(request.email())
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "AUTH_INVALID_CREDENTIALS", "Invalid email or password"));
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "AUTH_INVALID_CREDENTIALS", "Invalid email or password");
        }
        return tokenResponse(user);
    }

    private AuthResponse tokenResponse(User user) {
        return new AuthResponse(jwtService.generateToken(user), TOKEN_EXPIRATION_MINUTES, CurrentUserResponse.from(user));
    }
}

