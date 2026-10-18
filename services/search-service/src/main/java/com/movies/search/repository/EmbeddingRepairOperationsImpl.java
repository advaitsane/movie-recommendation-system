package com.movies.search.repository;

import com.movies.search.model.MovieSearchDocument;
import com.movies.search.model.MovieSearchDocument.Fields;
import java.util.List;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

/** Spring Data picks this up as the {@link EmbeddingRepairOperations} fragment by its name. */
class EmbeddingRepairOperationsImpl implements EmbeddingRepairOperations {

    private final MongoTemplate mongoTemplate;

    EmbeddingRepairOperationsImpl(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    public boolean setEmbeddingIfUnchanged(MovieSearchDocument document, List<Double> embedding) {
        Query unchanged = Query.query(Criteria.where(Fields.ID).is(document.getId())
                .and(Fields.PLOT_EMBEDDING).is(null)
                .and(Fields.TITLE).is(document.getTitle())
                .and(Fields.PLOT).is(document.getPlot())
                .and(Fields.FULLPLOT).is(document.getFullplot()));
        return mongoTemplate.updateFirst(unchanged, Update.update(Fields.PLOT_EMBEDDING, embedding),
                MovieSearchDocument.class).getModifiedCount() == 1;
    }
}
