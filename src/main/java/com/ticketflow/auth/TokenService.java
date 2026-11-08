package com.ticketflow.auth;

import com.ticketflow.user.dto.UserResponse;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
class TokenService {

    record AccessToken(String value, long expiresInSeconds) {
    }

    private final JwtEncoder jwtEncoder;
    private final JwtProperties properties;
    private final Clock clock;

    /** Claims: sub = user id, email, roles = ["ROLE_<role>"] (read back without extra prefix). */
    AccessToken createAccessToken(UserResponse user) {
        Instant now = clock.instant();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .issuedAt(now)
                .expiresAt(now.plus(properties.accessTokenTtl()))
                .subject(String.valueOf(user.id()))
                .claim("email", user.email())
                .claim("roles", List.of("ROLE_" + user.role().name()))
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String token = jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new AccessToken(token, properties.accessTokenTtl().toSeconds());
    }
}
