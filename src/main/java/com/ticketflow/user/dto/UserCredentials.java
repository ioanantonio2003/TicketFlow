package com.ticketflow.user.dto;

import com.ticketflow.user.Role;

/** What the login needs to verify a user. Internal: never returned from a controller. */
public record UserCredentials(Long id, String email, String passwordHash, Role role, boolean enabled) {

    @Override
    public String toString() {
        return "UserCredentials[id=" + id + ", role=" + role + "]"; // no email or hash in logs
    }
}
