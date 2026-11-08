package com.ticketflow.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Body of {@code /api/auth/refresh} and {@code /api/auth/logout}. */
public record RefreshTokenRequest(@NotBlank @Size(max = 200) String refreshToken) {

    @Override
    public String toString() {
        return "RefreshTokenRequest[]"; // never print the token
    }
}
