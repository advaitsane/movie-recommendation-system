package com.movies.search.embedding;

import com.movies.search.config.VoyageProperties;
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
 * {@link EmbeddingService} backed by Voyage AI's {@code POST /v1/embeddings}. Active when
 * {@code embedding.provider=voyage} (the default). Fails soft: an unconfigured
 * {@code VOYAGE_API_KEY} or a failed call returns empty results rather than throwing — such a
 * document is simply invisible to $vectorSearch while text/genre/year search keeps working.
 */
@Service
@ConditionalOnProperty(prefix = "embedding", name = "provider", havingValue = "voyage", matchIfMissing = true)
public class VoyageEmbeddingService implements EmbeddingService {

    private static final Logger logger = LoggerFactory.getLogger(VoyageEmbeddingService.class);

    private final RestClient voyageRestClient;
    private final String model;
    private final int dimensions;
    private final boolean enabled;

    public VoyageEmbeddingService(
            RestClient voyageRestClient,
            VoyageProperties properties) {
        this.voyageRestClient = voyageRestClient;
        this.model = properties.model();
        this.dimensions = properties.embeddingDimensions();
        this.enabled = properties.api().hasKey();
        if (!enabled) {
            logger.warn(
                    "VOYAGE_API_KEY is not configured — movies will be indexed without embeddings, "
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

    /**
     * Embeds a batch of movie texts (title + plot) for storage. Returns a list the same length
     * as {@code texts}, with a {@code null} entry wherever that text was blank or the batch
     * call failed — callers should save the document anyway with a null embedding rather than
     * drop it.
     */
    @Override
    public List<List<Double>> embedDocuments(List<String> texts) {
        return embed(texts, "document");
    }

    @Override
    public Optional<List<Double>> embedQuery(String text) {
        List<List<Double>> result = embed(List.of(text), "query");
        return result.isEmpty() || result.get(0) == null ? Optional.empty() : Optional.of(result.get(0));
    }

    private List<List<Double>> embed(List<String> texts, String inputType) {
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
            VoyageEmbeddingRequest request = new VoyageEmbeddingRequest(nonBlankTexts, model, inputType);
            VoyageEmbeddingResponse response = voyageRestClient.post()
                    .uri("/v1/embeddings")
                    .body(request)
                    .retrieve()
                    .body(VoyageEmbeddingResponse.class);

            if (response == null || response.data() == null) {
                logger.warn("Voyage embeddings call for {} text(s) returned no data", nonBlankTexts.size());
                return results;
            }

            response.data().stream()
                    .sorted(Comparator.comparingInt(VoyageEmbeddingResponse.EmbeddingData::index))
                    .forEach(data -> results.set(nonBlankIndexes.get(data.index()), data.embedding()));
            return results;
        } catch (Exception e) {
            logger.error(
                    "Voyage embeddings call failed for {} text(s): {}", nonBlankTexts.size(), e.getMessage(), e);
            return results;
        }
    }
}
