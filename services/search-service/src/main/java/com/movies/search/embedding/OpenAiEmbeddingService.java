package com.movies.search.embedding;

import com.movies.search.config.OpenAiProperties;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * {@link EmbeddingService} backed by OpenAI's {@code POST /v1/embeddings}. Active when
 * {@code embedding.provider=openai} — see {@link VoyageEmbeddingService} for the default. Unlike
 * Voyage, OpenAI has no query-vs-document distinction, but the same fail-soft contract applies:
 * an unconfigured or failing call returns null/empty rather than throwing, so a provider outage
 * never breaks text/genre/year search or the Kafka sync path.
 */
@Service
@ConditionalOnProperty(prefix = "embedding", name = "provider", havingValue = "openai")
public class OpenAiEmbeddingService implements EmbeddingService {

    private static final Logger logger = LoggerFactory.getLogger(OpenAiEmbeddingService.class);

    private final RestClient openAiRestClient;
    private final String model;
    private final int dimensions;
    private final boolean enabled;

    public OpenAiEmbeddingService(
            RestClient openAiRestClient,
            OpenAiProperties properties) {
        this.openAiRestClient = openAiRestClient;
        this.model = properties.model();
        this.dimensions = properties.embeddingDimensions();
        this.enabled = properties.api().hasKey();
        if (!enabled) {
            logger.warn(
                    "OPENAI_API_KEY is not configured — movies will be indexed without embeddings, "
                            + "and the vector-search endpoints will report 503 until it's set.");
        }
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public int getDimensions() {
        return dimensions;
    }

    @Override
    public List<List<Double>> embedDocuments(List<String> texts) {
        return embed(texts);
    }

    @Override
    public Optional<List<Double>> embedQuery(String text) {
        List<List<Double>> result = embed(List.of(text));
        return result.isEmpty() || result.get(0) == null ? Optional.empty() : Optional.of(result.get(0));
    }

    private List<List<Double>> embed(List<String> texts) {
        List<List<Double>> results = new ArrayList<>(texts.size());
        for (int i = 0; i < texts.size(); i++) {
            results.add(null);
        }
        if (!enabled) {
            return results;
        }

        List<Integer> nonBlankIndexes = new ArrayList<>();
        List<String> nonBlankTexts = new ArrayList<>();
        for (int i = 0; i < texts.size(); i++) {
            String text = texts.get(i);
            if (text != null && !text.isBlank()) {
                nonBlankIndexes.add(i);
                nonBlankTexts.add(text);
            }
        }
        if (nonBlankTexts.isEmpty()) {
            return results;
        }

        try {
            OpenAiEmbeddingRequest request = new OpenAiEmbeddingRequest(nonBlankTexts, model, dimensions);
            OpenAiEmbeddingResponse response = openAiRestClient.post()
                    .uri("/v1/embeddings")
                    .body(request)
                    .retrieve()
                    .body(OpenAiEmbeddingResponse.class);

            if (response == null || response.data() == null) {
                logger.warn("OpenAI embeddings call for {} text(s) returned no data", nonBlankTexts.size());
                return results;
            }

            response.data().stream()
                    .sorted(Comparator.comparingInt(OpenAiEmbeddingResponse.EmbeddingData::index))
                    .forEach(data -> results.set(nonBlankIndexes.get(data.index()), data.embedding()));
            return results;
        } catch (Exception e) {
            logger.error(
                    "OpenAI embeddings call failed for {} text(s): {}", nonBlankTexts.size(), e.getMessage(), e);
            return results;
        }
    }
}
