package com.movies.user.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * JWT signing configuration. {@code secret} has no committed default: it must be supplied via
 * the {@code JWT_SECRET} env var (docker-compose sets a dev value) or {@code jwt.secret} in a
 * gitignored {@code application-local.yml}. Validated here at binding time so a missing or weak
 * secret fails startup with a message naming the fix, rather than surfacing later as a generic
 * JJWT {@code WeakKeyException} — HS256 needs at least 32 bytes (256 bits) of key material.
 *
 * <p>{@code expiration} is a {@link Duration} ({@code 1h}, {@code 15m}, ...) and must be positive;
 * that check runs as a bean-validation constraint at binding time rather than in the constructor,
 * so unit tests can still build an already-expired token on purpose.
 */
@Validated
@ConfigurationProperties(prefix = "jwt")
public record JwtProperties(
        String secret,
        @NotNull Duration expiration
) {

    static final int MIN_SECRET_BYTES = 32;

    public JwtProperties {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "jwt.secret is not set. Provide it via the JWT_SECRET env var or jwt.secret in a "
                            + "gitignored application-local.yml (SPRING_PROFILES_ACTIVE=local).");
        }
        if (secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "jwt.secret must be at least " + MIN_SECRET_BYTES + " bytes for HS256.");
        }
    }

    @AssertTrue(message = "jwt.expiration must be a positive duration, e.g. 1h")
    public boolean isExpirationPositive() {
        return expiration == null || expiration.isPositive();
    }
}
