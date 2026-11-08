package com.ticketflow.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ticketflow.TestcontainersConfig;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Real PostgreSQL (Testcontainers) so the conditional UPDATE and the constraints behave as in production.
 * Every test runs in a transaction that is rolled back, so tests are independent.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TestcontainersConfig.class, RefreshTokenService.class, RefreshTokenServiceTest.TestBeans.class})
@ActiveProfiles("test")
class RefreshTokenServiceTest {

    private static final Instant START = Instant.parse("2030-01-01T10:00:00Z");

    @TestConfiguration
    @EnableConfigurationProperties(JwtProperties.class)
    static class TestBeans {
        @Bean
        MutableClock clock() {
            return new MutableClock(START);
        }
    }

    @Autowired
    private RefreshTokenService service;
    @Autowired
    private RefreshTokenRepository repository;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private Clock clock;

    private Long userId;
    private Long otherUserId;

    @BeforeEach
    void createUsers() {
        userId = insertUser("a-" + System.nanoTime() + "@example.com");
        otherUserId = insertUser("b-" + System.nanoTime() + "@example.com");
    }

    @Test
    void issueStoresOnlyTheSha256HashOfTheToken() {
        String raw = service.issue(userId);

        RefreshToken stored = repository.findByTokenHash(RefreshTokenService.hash(raw)).orElseThrow();
        assertThat(stored.getTokenHash()).hasSize(64).isNotEqualTo(raw);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from refresh_tokens where token_hash = ?", Integer.class, raw)).isZero();
        assertThat(stored.isRevoked()).isFalse();
        assertThat(stored.getExpiresAt()).isEqualTo(START.plus(Duration.ofDays(7)));
    }

    @Test
    void issuedTokensAreDifferentAndUrlSafe() {
        String first = service.issue(userId);
        String second = service.issue(userId);

        assertThat(first).isNotEqualTo(second).matches("[A-Za-z0-9_-]{43}"); // 32 bytes, Base64 URL, no padding
    }

    @Test
    void refreshRotatesTheToken() {
        String original = service.issue(userId);

        RefreshTokenService.Rotation rotation = service.refresh(original);

        assertThat(rotation.userId()).isEqualTo(userId);
        assertThat(rotation.newToken()).isNotEqualTo(original);
        assertThat(isRevoked(original)).isTrue();
        assertThat(isRevoked(rotation.newToken())).isFalse();
    }

    @Test
    void reusingARotatedTokenRevokesEveryTokenOfThatUser() {
        String original = service.issue(userId);
        String otherDevice = service.issue(userId);
        String rotated = service.refresh(original).newToken();
        String othersToken = service.issue(otherUserId);

        assertThatThrownBy(() -> service.refresh(original)).isInstanceOf(InvalidTokenException.class);

        assertThat(isRevoked(rotated)).isTrue();
        assertThat(isRevoked(otherDevice)).isTrue();
        assertThat(isRevoked(othersToken)).isFalse(); // other users are not affected
    }

    @Test
    void revokedTokenCannotBeRefreshed() {
        String raw = service.issue(userId);
        service.logout(raw);

        assertThatThrownBy(() -> service.refresh(raw)).isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void expiredTokenIsRejectedWithoutRevokingTheOthers() {
        String expiring = service.issue(userId);
        ((MutableClock) clock).advance(Duration.ofDays(6));
        String fresh = service.issue(userId);
        ((MutableClock) clock).advance(Duration.ofDays(2)); // `expiring` is now 8 days old, `fresh` 2 days

        assertThatThrownBy(() -> service.refresh(expiring)).isInstanceOf(InvalidTokenException.class);

        assertThat(isRevoked(fresh)).isFalse();
        assertThat(service.refresh(fresh).userId()).isEqualTo(userId);
    }

    @Test
    void unknownTokenIsRejected() {
        assertThatThrownBy(() -> service.refresh("this-token-was-never-issued"))
                .isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void logoutIsIdempotentAndIgnoresUnknownTokens() {
        String raw = service.issue(userId);

        service.logout(raw);
        service.logout(raw);
        service.logout("unknown");

        assertThat(isRevoked(raw)).isTrue();
    }

    @Test
    void revokeIfActiveSucceedsOnlyOnce() {
        String raw = service.issue(userId);
        Long id = repository.findByTokenHash(RefreshTokenService.hash(raw)).orElseThrow().getId();

        assertThat(repository.revokeIfActive(id)).isEqualTo(1);
        assertThat(repository.revokeIfActive(id)).isZero(); // what a concurrent second refresh would see
    }

    @Test
    void deleteExpiredRemovesOnlyExpiredTokens() {
        String old = service.issue(userId);
        ((MutableClock) clock).advance(Duration.ofDays(6));
        String recent = service.issue(userId);
        ((MutableClock) clock).advance(Duration.ofDays(2));

        int deleted = service.deleteExpired();

        assertThat(deleted).isEqualTo(1);
        assertThat(repository.findByTokenHash(RefreshTokenService.hash(old))).isEmpty();
        assertThat(repository.findByTokenHash(RefreshTokenService.hash(recent))).isPresent();
    }

    private boolean isRevoked(String raw) {
        return jdbcTemplate.queryForObject("select revoked from refresh_tokens where token_hash = ?", Boolean.class,
                RefreshTokenService.hash(raw));
    }

    private Long insertUser(String email) {
        return jdbcTemplate.queryForObject(
                "insert into users (email, password_hash, display_name, role, created_at, updated_at) "
                        + "values (?, 'x', 'x', 'CUSTOMER', now(), now()) returning id", Long.class, email);
    }
}
