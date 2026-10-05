package com.movies.user.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.movies.user.config.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("JwtService Unit Tests")
class JwtServiceTest {

    private static final String SECRET = "unit-test-jwt-signing-secret-at-least-32-bytes-long";

    @Test
    @DisplayName("issueToken produces a token whose claims round-trip through parse")
    void issueToken_roundTripsThroughParse() {
        JwtService jwtService = new JwtService(new JwtProperties(SECRET, Duration.ofMinutes(1)));

        String token = jwtService.issueToken(42L, "user@example.com");
        Jws<Claims> parsed = jwtService.parse(token);

        assertThat(parsed.getPayload().getSubject()).isEqualTo("42");
        assertThat(parsed.getPayload().get("email", String.class)).isEqualTo("user@example.com");
    }

    @Test
    @DisplayName("parse rejects a token signed with a different secret")
    void parse_wrongSecret_throws() {
        JwtService issuer = new JwtService(new JwtProperties(SECRET, Duration.ofMinutes(1)));
        JwtService verifier = new JwtService(new JwtProperties("a-completely-different-32-byte-plus-secret-value", Duration.ofMinutes(1)));

        String token = issuer.issueToken(1L, "a@b.com");

        assertThrows(JwtException.class, () -> verifier.parse(token));
    }

    @Test
    @DisplayName("parse rejects an expired token")
    void parse_expiredToken_throws() {
        JwtService jwtService = new JwtService(new JwtProperties(SECRET, Duration.ofSeconds(-1)));

        String token = jwtService.issueToken(1L, "a@b.com");

        assertThrows(ExpiredJwtException.class, () -> jwtService.parse(token));
    }
}
