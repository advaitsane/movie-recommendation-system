package com.movies.gateway.security;

import com.movies.gateway.config.GatewayJwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Component;

/**
 * Validates JWTs issued by user-service's {@code JwtService} — same secret, same HS256 signing,
 * same claim shape (subject = user id, {@code email} custom claim). Kept as a standalone class
 * (not shared via a common library module) because this repo has no shared module between
 * services; duplicating this ~15-line class is cheaper than introducing one for a single class.
 */
@Component
public class JwtValidator {

    private final SecretKey key;

    public JwtValidator(GatewayJwtProperties properties) {
        this.key = Keys.hmacShaKeyFor(properties.secret().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * @throws JwtException if the token is malformed, expired, or signed with a different key.
     */
    public Jws<Claims> validate(String token) {
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token);
    }
}
