package com.ticketflow.auth.dto;

import com.ticketflow.user.dto.UserResponse;

/** {@code expiresIn} is the access token lifetime in seconds. */
public record AuthResponse(String accessToken, String refreshToken, String tokenType, long expiresIn,
                           UserResponse user) {

    @Override
    public String toString() {
        return "AuthResponse[user=" + user + "]"; // never print tokens
    }
}
