package com.movies.gateway.config;

import java.nio.charset.StandardCharsets;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Only the signing secret is needed here — unlike user-service's {@code JwtProperties}, the
 * gateway never issues tokens, only validates ones already carrying their own expiration claim.
 *
 * <p>No committed default: the secret must come from the {@code JWT_SECRET} env var
 * (docker-compose sets a dev value shared with user-service) or {@code jwt.secret} in a gitignored
 * {@code application-local.yml}. Validated at binding time so a missing or weak secret fails
 * startup with an actionable message — HS256 needs at least 32 bytes of key material.
 */
@ConfigurationProperties(prefix = "jwt")
public record GatewayJwtProperties(String secret) {

    static final int MIN_SECRET_BYTES = 32;

    public GatewayJwtProperties {
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
}
