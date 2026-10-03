package com.movies.catalog.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.bson.types.ObjectId;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Domain model for a movie document in the movies collection, including nested objects
 * for awards, IMDB ratings, and Rotten Tomatoes ratings.
 */
@Getter
@Setter
@ToString(onlyExplicitlyIncluded = true)
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@Builder
@AllArgsConstructor(access = AccessLevel.PROTECTED) // needed for Spring Data and MongoDB mapping
@NoArgsConstructor(access = AccessLevel.PROTECTED) // needed for Spring Data and MongoDB mapping
@Document(collection = "movies")
public class Movie {

    /**
     * Field name constants for MongoDB queries, filters, and indexes — avoids magic
     * strings and enables IDE "Find Usages" on field references.
     */
    public static class Fields {
        public static final String ID = "_id";
        public static final String TITLE = "title";
        public static final String YEAR = "year";
        public static final String PLOT = "plot";
        public static final String FULLPLOT = "fullplot";
        public static final String RELEASED = "released";
        public static final String RUNTIME = "runtime";
        public static final String POSTER = "poster";
        public static final String GENRES = "genres";
        public static final String DIRECTORS = "directors";
        public static final String WRITERS = "writers";
        public static final String CAST = "cast";
        public static final String COUNTRIES = "countries";
        public static final String LANGUAGES = "languages";
        public static final String RATED = "rated";
        public static final String AWARDS = "awards";
        public static final String IMDB = "imdb";
        public static final String IMDB_RATING = "imdb.rating";
        public static final String TOMATOES = "tomatoes";
        public static final String METACRITIC = "metacritic";
        public static final String TYPE = "type";

        private Fields() {
            // Private constructor to prevent instantiation
        }
    }

    @JsonProperty("_id")
    @Id
    @ToString.Include
    @EqualsAndHashCode.Include
    private ObjectId id;

    @ToString.Include
    private String title;

    @ToString.Include
    private Integer year;

    private String plot;

    private String fullplot;

    /**
     * A calendar date only (no time-of-day/zone), matching how MongoDB stores it —
     * mapped via Spring Data's Jsr310 LocalDateCodec.
     */
    private LocalDate released;

    private Integer runtime;

    private String poster;

    private List<String> genres;

    private List<String> directors;

    private List<String> writers;

    private List<String> cast;

    private List<String> countries;

    private List<String> languages;

    private String rated;

    private Awards awards;

    private Imdb imdb;

    private Tomatoes tomatoes;

    private Integer metacritic;

    private String type;

    /**
     * Awards summary: win/nomination counts plus a free-text description.
     */
    @Getter
    @Setter
    @Builder
    @AllArgsConstructor(access = AccessLevel.PROTECTED) // needed for Spring Data and MongoDB mapping
    @NoArgsConstructor(access = AccessLevel.PROTECTED) // needed for Spring Data and MongoDB mapping
    public static class Awards {
        private Integer wins;
        private Integer nominations;
        private String text;
    }

    /**
     * IMDB rating (0.0-10.0), vote count, and IMDB's own numeric ID.
     */
    @Getter
    @Setter
    @Builder
    @AllArgsConstructor(access = AccessLevel.PROTECTED) // needed for Spring Data and MongoDB mapping
    @NoArgsConstructor(access = AccessLevel.PROTECTED) // needed for Spring Data and MongoDB mapping
    public static class Imdb {
        private Double rating;
        private Integer votes;
        private Integer id;
    }

    /**
     * Rotten Tomatoes ratings: separate viewer/critic breakdowns plus aggregate
     * fresh/rotten counts and the production company.
     */
    @Getter
    @Setter
    @Builder
    @AllArgsConstructor(access = AccessLevel.PROTECTED) // needed for Spring Data and MongoDB mapping
    @NoArgsConstructor(access = AccessLevel.PROTECTED) // needed for Spring Data and MongoDB mapping
    public static class Tomatoes {
        private Viewer viewer;
        private Critic critic;
        private Integer fresh;
        private Integer rotten;
        private String production;

        /**
         * Stored as BSON DateTime; Instant gives an immutable, UTC-only representation.
         */
        private Instant lastUpdated;

        @Getter
        @Setter
        @Builder
        @AllArgsConstructor(access = AccessLevel.PROTECTED) // needed for Spring Data and MongoDB mapping
        @NoArgsConstructor(access = AccessLevel.PROTECTED) // needed for Spring Data and MongoDB mapping
        public static class Viewer {
            private Double rating;
            private Integer numReviews;
            private Integer meter;
        }

        @Getter
        @Setter
        @Builder
        @AllArgsConstructor(access = AccessLevel.PROTECTED) // needed for Spring Data and MongoDB mapping
        @NoArgsConstructor(access = AccessLevel.PROTECTED) // needed for Spring Data and MongoDB mapping
        public static class Critic {
            private Double rating;
            private Integer numReviews;
            private Integer meter;
        }
    }
}
