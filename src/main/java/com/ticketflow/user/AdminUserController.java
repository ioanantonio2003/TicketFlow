package com.ticketflow.user;

import com.ticketflow.user.dto.RoleChangeRequest;
import com.ticketflow.user.dto.UserResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Restricted to ADMIN by the URL rule {@code /api/admin/**} in SecurityConfig. */
@RestController
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
class AdminUserController {

    private final UserService userService;

    @PatchMapping("/{id}/role")
    UserResponse changeRole(@PathVariable Long id, @Valid @RequestBody RoleChangeRequest request) {
        return userService.changeRole(id, request.role());
    }
}
