package com.ticketflow.user.dto;

import com.ticketflow.user.Role;

public record UserResponse(Long id, String email, String displayName, Role role) {
}
