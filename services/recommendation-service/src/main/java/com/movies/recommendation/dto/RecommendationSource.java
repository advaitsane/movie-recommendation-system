package com.movies.recommendation.dto;

/**
 * Which signal(s) contributed to a recommended movie — surfaced on the response so the blend
 * is explainable rather than a black box (a deliberate design choice, per docs/adr/0005).
 */
public enum RecommendationSource {
    CONTENT,
    COLLABORATIVE,
    BOTH,
    /** Cold-start fallback: this user has no ratings yet, so this is a global-popularity pick. */
    POPULAR
}
