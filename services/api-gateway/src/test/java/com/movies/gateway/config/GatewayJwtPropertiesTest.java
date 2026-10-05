package com.movies.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class GatewayJwtPropertiesTest {

    @Test
    void missingSecretFailsWithAMessageNamingJwtSecret() {
        assertThatThrownBy(() -> new GatewayJwtProperties(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET");
        assertThatThrownBy(() -> new GatewayJwtProperties(""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET");
    }

    @Test
    void secretShorterThan32BytesIsRejected() {
        assertThatThrownBy(() -> new GatewayJwtProperties("too-short"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 bytes");
    }

    @Test
    void secretOfAtLeast32BytesIsAccepted() {
        String secret = "a".repeat(32);
        assertThat(new GatewayJwtProperties(secret).secret()).isEqualTo(secret);
    }
}
