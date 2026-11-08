package com.ticketflow.user.dto;

import com.ticketflow.user.Role;
import jakarta.validation.constraints.NotNull;

public record RoleChangeRequest(@NotNull Role role) {
}
