package com.ticketflow.user;

import com.ticketflow.common.exception.BusinessRuleException;
import com.ticketflow.common.persistence.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "users") // "user" is a reserved word in PostgreSQL
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class AppUser extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Column(name = "display_name", nullable = false, length = 100)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role;

    @Column(nullable = false)
    private boolean enabled;

    @Version
    private Long version;

    private AppUser(String email, String passwordHash, String displayName, Role role) {
        this.email = email;
        this.passwordHash = passwordHash;
        this.displayName = displayName;
        this.role = role;
        this.enabled = true;
    }

    /** Every self-registered user is a CUSTOMER: the role is never chosen by the client. */
    static AppUser newCustomer(String email, String passwordHash, String displayName) {
        return new AppUser(email, passwordHash, displayName, Role.CUSTOMER);
    }

    static AppUser newAdmin(String email, String passwordHash, String displayName) {
        return new AppUser(email, passwordHash, displayName, Role.ADMIN);
    }

    /** Admins are created only by the bootstrap, so the API can neither create nor demote one. */
    void changeRole(Role newRole) {
        if (role == Role.ADMIN || newRole == Role.ADMIN) {
            throw new BusinessRuleException("The ADMIN role cannot be granted or revoked through the API.");
        }
        this.role = newRole;
    }

    // Id-based equality; constant hashCode so the value stays stable before and after the id is assigned.
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        return o instanceof AppUser other && id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
