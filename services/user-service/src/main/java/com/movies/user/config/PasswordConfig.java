package com.movies.user.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Password hashing only — deliberately not {@code spring-boot-starter-security}. That starter
 * auto-configures a filter chain that would require every endpoint (including registration and
 * login themselves) to be explicitly permitted, for no benefit here: request-level auth
 * enforcement is deferred to when api-gateway exists to actually validate the issued JWT on
 * incoming requests (see ADR-0006).
 */
@Configuration
public class PasswordConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
