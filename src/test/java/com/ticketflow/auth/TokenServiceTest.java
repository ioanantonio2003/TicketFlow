package com.ticketflow.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ticketflow.user.Role;
import com.ticketflow.user.dto.UserResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

/** Uses the real encoder and decoder from JwtConfig, so it checks the token round trip. */
class TokenServiceTest {

    private static final Instant NOW = Instant.parse("2030-01-01T10:00:00Z");
    private static final String SECRET = "unit-test-secret-0123456789abcdef-0123";

    private final UserResponse ana = new UserResponse(42L, "ana@example.com", "Ana", Role.ORGANIZER);

    private TokenService tokenService(Instant now, String secret) {
        JwtProperties properties = new JwtProperties(secret, "ticketflow", Duration.ofMinutes(15), Duration.ofDays(7));
        return new TokenService(new JwtConfig().jwtEncoder(properties), properties,
                Clock.fixed(now, ZoneOffset.UTC));
    }

    private JwtDecoder decoder(String secret) {
        return new JwtConfig().jwtDecoder(new JwtProperties(secret, "ticketflow", Duration.ofMinutes(15), Duration.ofDays(7)));
    }

    @Test
    void tokenCarriesSubjectEmailRolesAndExpiry() {
        Instant now = Instant.now(); // the decoder validates against the real clock
        TokenService.AccessToken token = tokenService(now, SECRET).createAccessToken(ana);

        Jwt jwt = decoder(SECRET).decode(token.value());

        assertThat(jwt.getSubject()).isEqualTo("42");
        assertThat(jwt.<String>getClaim("email")).isEqualTo("ana@example.com");
        assertThat(jwt.getClaimAsStringList("roles")).containsExactly("ROLE_ORGANIZER");
        assertThat(jwt.getClaimAsString("iss")).isEqualTo("ticketflow");
        assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt())).isEqualTo(Duration.ofMinutes(15));
        assertThat(token.expiresInSeconds()).isEqualTo(900);
    }

    @Test
    void tokenSignedWithAnotherSecretIsRejected() {
        String token = tokenService(Instant.now(), "another-secret-0123456789abcdef-0123456").createAccessToken(ana).value();

        assertThatThrownBy(() -> decoder(SECRET).decode(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void expiredTokenIsRejected() {
        String token = tokenService(Instant.now().minus(Duration.ofHours(1)), SECRET).createAccessToken(ana).value();

        assertThatThrownBy(() -> decoder(SECRET).decode(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void tokenFromAnotherIssuerIsRejected() {
        JwtProperties other = new JwtProperties(SECRET, "someone-else", Duration.ofMinutes(15), Duration.ofDays(7));
        String token = new TokenService(new JwtConfig().jwtEncoder(other), other, Clock.systemUTC())
                .createAccessToken(ana).value();

        assertThatThrownBy(() -> decoder(SECRET).decode(token)).isInstanceOf(JwtException.class);
    }
}
