package com.movies.user.dto;

import com.movies.user.event.UserActivityType;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;

/**
 * Request body for {@code POST /api/users/{id}/activity}. Which of {@code movieId}/{@code query}
 * is required depends on {@code type} (VIEW needs movieId, SEARCH needs query) — checked in
 * {@code UserServiceImpl#recordActivity}, not here, since bean validation can't easily express
 * "exactly one of these two depending on a third field."
 */
@Builder
public record RecordActivityRequest(

        @NotNull(message = "type is required")
        UserActivityType type,

        String movieId,

        String query) {
}
