package com.movies.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.movies.gateway.config.GatewayJwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.Test;

class JwtValidatorTest {

    private static final String SECRET = "test-only-jwt-signing-secret-at-least-32-bytes-long-xyz";

    private final JwtValidator validator = new JwtValidator(new GatewayJwtProperties(SECRET));

    @Test
    void validatesTokenSignedWithTheSameSecret() {
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        Instant now = Instant.now();
        String token = Jwts.builder()
                .subject("42")
                .claim("email", "user@example.com")
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(3600)))
                .signWith(key)
                .compact();

        Claims claims = validator.validate(token).getPayload();

        assertThat(claims.getSubject()).isEqualTo("42");
        assertThat(claims.get("email", String.class)).isEqualTo("user@example.com");
    }

    @Test
    void rejectsTokenSignedWithADifferentSecret() {
        SecretKey otherKey = Keys.hmacShaKeyFor(
                "a-completely-different-signing-secret-also-32-bytes".getBytes(StandardCharsets.UTF_8));
        Instant now = Instant.now();
        String token = Jwts.builder()
                .subject("42")
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(3600)))
                .signWith(otherKey)
                .compact();

        assertThatThrownBy(() -> validator.validate(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsExpiredToken() {
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        Instant past = Instant.now().minusSeconds(7200);
        String token = Jwts.builder()
                .subject("42")
                .issuedAt(Date.from(past))
                .expiration(Date.from(past.plusSeconds(3600)))
                .signWith(key)
                .compact();

        assertThatThrownBy(() -> validator.validate(token)).isInstanceOf(ExpiredJwtException.class);
    }
}
