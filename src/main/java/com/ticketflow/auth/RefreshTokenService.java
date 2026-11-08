package com.ticketflow.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Opaque refresh tokens with rotation: every use revokes the presented token and issues a new one.
 * Presenting an already revoked token means it was stolen or replayed, so all tokens of that user
 * are revoked (reuse detection) and the user has to log in again.
 */
@Slf4j
@Service
@RequiredArgsConstructor
class RefreshTokenService {

    private static final int TOKEN_BYTES = 32;

    /** The user the presented token belonged to, and the new raw token that replaces it. */
    record Rotation(Long userId, String newToken) {
    }

    private final RefreshTokenRepository repository;
    private final JwtProperties properties;
    private final Clock clock;
    private final SecureRandom secureRandom = new SecureRandom();

    /** Creates a token for the user and returns the raw value: the only time it exists in plain text. */
    @Transactional
    String issue(Long userId) {
        byte[] random = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(random);
        String rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(random);

        Instant now = clock.instant();
        repository.save(new RefreshToken(userId, hash(rawToken), now, now.plus(properties.refreshTokenTtl())));
        return rawToken;
    }

    /**
     * noRollbackFor: when reuse is detected we revoke all tokens and then throw. Without it the
     * exception would roll the revocation back and the stolen tokens would stay valid.
     */
    @Transactional(noRollbackFor = InvalidTokenException.class)
    Rotation refresh(String rawToken) {
        RefreshToken token = repository.findByTokenHash(hash(rawToken))
                .orElseThrow(() -> new InvalidTokenException("Unknown refresh token"));

        if (token.isRevoked()) {
            return reuseDetected(token);
        }
        if (token.isExpiredAt(clock.instant())) {
            throw new InvalidTokenException("Expired refresh token");
        }
        // Conditional update instead of "check in Java, then write": if a concurrent request already
        // rotated this token, 0 rows change and we treat it as reuse.
        if (repository.revokeIfActive(token.getId()) == 0) {
            return reuseDetected(token);
        }
        return new Rotation(token.getUserId(), issue(token.getUserId()));
    }

    /** Idempotent: an unknown or already revoked token is simply ignored. */
    @Transactional
    void logout(String rawToken) {
        repository.findByTokenHash(hash(rawToken)).ifPresent(token -> repository.revokeIfActive(token.getId()));
    }

    /** Revoked tokens are kept until they expire, because reuse detection needs to recognise them. */
    @Transactional
    int deleteExpired() {
        return repository.deleteExpiredBefore(clock.instant());
    }

    private Rotation reuseDetected(RefreshToken token) {
        int revoked = repository.revokeAllByUser(token.getUserId());
        log.warn("Refresh token reuse detected for user {}: revoked {} active token(s)", token.getUserId(), revoked);
        throw new InvalidTokenException("Refresh token reuse detected");
    }

    // A fast hash is fine here: the token is 256 random bits (not guessable like a password), and the
    // hash must be deterministic so the token can be looked up by it.
    static String hash(String rawToken) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }
}
