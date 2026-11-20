package de.aidocuments.document;

import static org.springframework.data.mongodb.core.aggregation.Aggregation.group;
import static org.springframework.data.mongodb.core.aggregation.Aggregation.limit;
import static org.springframework.data.mongodb.core.aggregation.Aggregation.match;
import static org.springframework.data.mongodb.core.aggregation.Aggregation.newAggregation;
import static org.springframework.data.mongodb.core.aggregation.Aggregation.sort;
import static org.springframework.data.mongodb.core.aggregation.Aggregation.unwind;

import de.aidocuments.document.DocumentDtos.FacetsDto;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.AggregationOperation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.stereotype.Component;

/** Zählt, wie oft Dokumentarten, Absender und Schlagworte vorkommen – Grundlage der Filterleiste. */
@Component
public class DocumentFacets {

    private final MongoTemplate mongo;

    public DocumentFacets(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    public FacetsDto all() {
        return new FacetsDto(categories(), correspondents(50), tags(100));
    }

    public List<FacetCount> categories() {
        return count("category", false, 100);
    }

    public List<FacetCount> correspondents(int max) {
        return count("correspondent", false, max);
    }

    public List<FacetCount> tags(int max) {
        return count("tags", true, max);
    }

    private List<FacetCount> count(String field, boolean array, int max) {
        List<AggregationOperation> pipeline = new ArrayList<>();
        if (array) {
            pipeline.add(unwind(field));
        } else {
            pipeline.add(match(Criteria.where(field).ne(null)));
        }
        pipeline.add(group(field).count().as("count"));
        pipeline.add(sort(Sort.by(Sort.Order.desc("count"), Sort.Order.asc("_id"))));
        pipeline.add(limit(max));
        return mongo.aggregate(newAggregation(pipeline), "documents", org.bson.Document.class)
                .getMappedResults().stream()
                .map(row -> new FacetCount(String.valueOf(row.get("_id")), ((Number) row.get("count")).longValue()))
                .toList();
    }
}
