package com.movies.review.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.movies.review.ReviewServiceApplication;
import com.movies.review.dto.CreateReviewRequest;
import com.movies.review.model.OutboxEvent;
import com.movies.review.outbox.OutboxPoller;
import com.movies.review.repository.OutboxEventRepository;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Proves the full write-then-poll-then-publish outbox path end to end: POST a review (which
 * writes the {@code reviews} row + an {@code outbox_events} row in one transaction), invoke
 * the poller directly (faster/less flaky than waiting on its {@code @Scheduled} cadence), then
 * consume the message from an embedded Kafka broker and assert its {@code eventId} matches
 * the row, and that the row is now marked published in Postgres.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ContextConfiguration(classes = ReviewServiceApplication.class)
@Import(ReviewServicePostgresTestContainersConfig.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EmbeddedKafka(
        partitions = 1,
        topics = {"review.created", "rating.updated"},
        bootstrapServersProperty = "spring.kafka.bootstrap-servers")
@DisplayName("Outbox-to-Kafka Integration Test")
class OutboxKafkaIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private OutboxPoller outboxPoller;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private EmbeddedKafkaBroker embeddedKafkaBroker;

    private Consumer<String, String> consumer;

    @BeforeEach
    void setUpConsumer() {
        Map<String, Object> consumerProps =
                KafkaTestUtils.consumerProps(embeddedKafkaBroker, "outbox-test-group", true);
        consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        consumer = new KafkaConsumer<>(consumerProps);
        embeddedKafkaBroker.consumeFromAnEmbeddedTopic(consumer, "review.created");
    }

    @AfterEach
    void tearDownConsumer() {
        consumer.close();
    }

    @Test
    @DisplayName("A created review is published to review.created with a matching eventId, and marked published")
    void createReview_publishesToKafkaViaOutboxPoller() throws Exception {
        CreateReviewRequest request = CreateReviewRequest.builder()
                .userId("user-outbox-1")
                .movieId("507f1f77bcf86cd799439099")
                .rating(4)
                .reviewText("Great outbox demo")
                .build();

        String createResponse = mockMvc.perform(post("/api/reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        Long reviewId = objectMapper.readTree(createResponse).at("/id").asLong();

        outboxPoller.pollAndPublish();

        ConsumerRecord<String, String> record =
                KafkaTestUtils.getSingleRecord(consumer, "review.created", Duration.ofSeconds(10));
        assertThat(record.key()).isEqualTo("507f1f77bcf86cd799439099");

        var payload = objectMapper.readTree(record.value());
        assertThat(payload.at("/reviewId").asLong()).isEqualTo(reviewId);
        assertThat(payload.at("/eventType").asText()).isEqualTo("REVIEW_CREATED");
        assertThat(payload.at("/eventId").asText()).isNotBlank();

        Optional<OutboxEvent> outboxRow = outboxEventRepository.findByAggregateId(reviewId);
        assertThat(outboxRow).isPresent();
        assertThat(outboxRow.get().getPublishedAt()).isNotNull();
    }
}
