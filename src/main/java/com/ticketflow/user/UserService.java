package com.ticketflow.user;

import com.ticketflow.common.exception.ConflictException;
import com.ticketflow.common.exception.ResourceNotFoundException;
import com.ticketflow.user.dto.UserCredentials;
import com.ticketflow.user.dto.UserResponse;
import java.util.Locale;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Public entry point of the user feature. Other features (auth) use only this class, never the
 * repository or the entity. Emails are normalized here, in one place. Passwords arrive already hashed.
 */
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public UserResponse findById(Long id) {
        return toResponse(getEntity(id));
    }

    @Transactional(readOnly = true)
    public UserResponse getMe(Long currentUserId) {
        return findById(currentUserId);
    }

    @Transactional(readOnly = true)
    public Optional<UserCredentials> findCredentialsByEmail(String email) {
        return userRepository.findByEmail(normalizeEmail(email))
                .map(u -> new UserCredentials(u.getId(), u.getEmail(), u.getPasswordHash(), u.getRole(),
                        u.isEnabled()));
    }

    @Transactional
    public UserResponse createCustomer(String email, String passwordHash, String displayName) {
        String normalized = normalizeEmail(email);
        assertEmailFree(normalized);
        return toResponse(userRepository.save(AppUser.newCustomer(normalized, passwordHash, displayName.trim())));
    }

    @Transactional
    public UserResponse createAdmin(String email, String passwordHash, String displayName) {
        String normalized = normalizeEmail(email);
        assertEmailFree(normalized);
        return toResponse(userRepository.save(AppUser.newAdmin(normalized, passwordHash, displayName.trim())));
    }

    @Transactional(readOnly = true)
    public boolean adminExists() {
        return userRepository.existsByRole(Role.ADMIN);
    }

    @Transactional
    public UserResponse changeRole(Long id, Role newRole) {
        AppUser user = getEntity(id);
        user.changeRole(newRole);
        return toResponse(user); // dirty checking flushes the change at commit
    }

    private AppUser getEntity(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User not found."));
    }

    // The unique index is the real guard against a race; this check gives a clean 409 in the normal case.
    private void assertEmailFree(String normalizedEmail) {
        if (userRepository.existsByEmail(normalizedEmail)) {
            throw new ConflictException("Email is already registered.");
        }
    }

    private static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private static UserResponse toResponse(AppUser user) {
        return new UserResponse(user.getId(), user.getEmail(), user.getDisplayName(), user.getRole());
    }
}
