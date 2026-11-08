package com.ticketflow.auth;

import com.ticketflow.user.Role;
import com.ticketflow.user.dto.UserCredentials;
import java.util.Collection;
import java.util.List;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/** Spring Security view of a user; carries the id so login does not need a second lookup. */
record AuthenticatedUser(Long id, String email, String passwordHash, Role role, boolean enabled)
        implements UserDetails {

    static AuthenticatedUser from(UserCredentials credentials) {
        return new AuthenticatedUser(credentials.id(), credentials.email(), credentials.passwordHash(),
                credentials.role(), credentials.enabled());
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public String toString() {
        return "AuthenticatedUser[id=" + id + ", role=" + role + "]"; // no email or hash in logs
    }
}
