package com.movies.user.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("JwtProperties Unit Tests")
class JwtPropertiesTest {

    @Test
    @DisplayName("missing secret fails with a message naming JWT_SECRET")
    void missingSecretFails() {
        assertThatThrownBy(() -> new JwtProperties(null, Duration.ofMinutes(1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET");
        assertThatThrownBy(() -> new JwtProperties("  ", Duration.ofMinutes(1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET");
    }

    @Test
    @DisplayName("secret shorter than 32 bytes is rejected")
    void shortSecretFails() {
        assertThatThrownBy(() -> new JwtProperties("too-short", Duration.ofMinutes(1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 bytes");
    }

    @Test
    @DisplayName("secret of at least 32 bytes is accepted")
    void validSecretAccepted() {
        String secret = "a".repeat(32);
        assertThat(new JwtProperties(secret, Duration.ofMinutes(1)).secret()).isEqualTo(secret);
    }
}
