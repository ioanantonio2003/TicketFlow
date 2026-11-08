package com.ticketflow.auth;

import com.ticketflow.auth.dto.AuthResponse;
import com.ticketflow.auth.dto.LoginRequest;
import com.ticketflow.auth.dto.RefreshTokenRequest;
import com.ticketflow.auth.dto.RegisterRequest;
import com.ticketflow.user.dto.UserResponse;
import jakarta.validation.Valid;
import java.net.URI;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
class AuthController {

    private final AuthService authService;

    @PostMapping("/register")
    ResponseEntity<UserResponse> register(@Valid @RequestBody RegisterRequest request) {
        UserResponse user = authService.register(request);
        // there is no public per-user URL; /api/users/me is where the new user reads their profile
        return ResponseEntity.created(URI.create("/api/users/me")).body(user);
    }

    @PostMapping("/login")
    AuthResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    @PostMapping("/refresh")
    AuthResponse refresh(@Valid @RequestBody RefreshTokenRequest request) {
        return authService.refresh(request);
    }

    /** 204 whether or not the token existed: logging out twice is not an error. */
    @PostMapping("/logout")
    ResponseEntity<Void> logout(@Valid @RequestBody RefreshTokenRequest request) {
        authService.logout(request);
        return ResponseEntity.noContent().build();
    }
}
