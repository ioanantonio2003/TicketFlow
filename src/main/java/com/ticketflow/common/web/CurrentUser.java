package com.ticketflow.common.web;

import org.springframework.security.oauth2.jwt.Jwt;

/** Reads the caller's id from the access token (the JWT subject is the user id). */
public final class CurrentUser {

    private CurrentUser() {
    }

    public static Long id(Jwt jwt) {
        return Long.valueOf(jwt.getSubject());
    }
}
