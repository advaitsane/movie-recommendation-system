package com.movies.user.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.movies.user.model.User;
import java.time.Instant;
import lombok.Builder;

/**
 * Wire-level shape for every endpoint that hands back a user. Keeps the JPA {@link User}
 * entity (and its {@code passwordHash}) out of the response body; {@link #from(User)} maps
 * one to the other.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Builder
public record UserResponse(
        Long id,
        String email,
        String displayName,
        Instant createdAt) {

    public static UserResponse from(User user) {
        return UserResponse.builder()
                .id(user.getId())
                .email(user.getEmail())
                .displayName(user.getDisplayName())
                .createdAt(user.getCreatedAt())
                .build();
    }
}
