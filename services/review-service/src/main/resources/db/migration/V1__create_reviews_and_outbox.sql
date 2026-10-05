-- reviews: one row per (user_id, movie_id) — a second review for the same pair is
-- rejected by the API (409) rather than allowed to accumulate, so PATCH is the only way
-- to change an existing rating/review.
CREATE TABLE reviews (
    id           BIGSERIAL PRIMARY KEY,
    user_id      VARCHAR(64)  NOT NULL,
    movie_id     VARCHAR(24)  NOT NULL, -- catalog-service's Mongo ObjectId hex string; opaque here, no FK
    rating       INTEGER      NOT NULL CHECK (rating BETWEEN 1 AND 5),
    review_text  TEXT,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_reviews_user_movie UNIQUE (user_id, movie_id)
);

CREATE INDEX idx_reviews_movie_id ON reviews (movie_id);
CREATE INDEX idx_reviews_user_id  ON reviews (user_id);

-- outbox_events: the transactional outbox. published_at IS NULL is the entire state
-- machine — no separate status column. event_id is generated in application code (not a
-- DB default) because it must already be known so it can be embedded inside payload at
-- insert time; kafka_key is its own column so the poller never needs to parse payload to
-- know how to key the Kafka send.
CREATE TABLE outbox_events (
    id               BIGSERIAL PRIMARY KEY,
    event_id         UUID        NOT NULL UNIQUE,
    aggregate_type   VARCHAR(50) NOT NULL DEFAULT 'review',
    aggregate_id     BIGINT      NOT NULL,
    kafka_key        VARCHAR(24) NOT NULL,
    event_type       VARCHAR(30) NOT NULL,
    payload          JSONB       NOT NULL,
    occurred_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at     TIMESTAMPTZ,
    publish_attempts INT         NOT NULL DEFAULT 0
);

-- Partial index: the poller only ever queries WHERE published_at IS NULL, so this index
-- stays sized to the (small) unpublished backlog instead of growing with the full history.
CREATE INDEX idx_outbox_unpublished ON outbox_events (id) WHERE published_at IS NULL;
