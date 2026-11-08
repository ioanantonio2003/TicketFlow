package com.ticketflow.auth;

import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /**
     * Atomically revokes a token that is still active. Returns 0 when somebody else revoked it first,
     * so two concurrent refreshes with the same token cannot both succeed (same idea as the seat UPDATE).
     */
    @Modifying(clearAutomatically = true)
    @Query("update RefreshToken t set t.revoked = true where t.id = :id and t.revoked = false")
    int revokeIfActive(@Param("id") Long id);

    @Modifying(clearAutomatically = true)
    @Query("update RefreshToken t set t.revoked = true where t.userId = :userId and t.revoked = false")
    int revokeAllByUser(@Param("userId") Long userId);

    @Modifying
    @Query("delete from RefreshToken t where t.expiresAt < :now")
    int deleteExpiredBefore(@Param("now") Instant now);
}
