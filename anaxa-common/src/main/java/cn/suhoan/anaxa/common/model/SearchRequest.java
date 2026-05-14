package cn.suhoan.anaxa.common.model;

import cn.suhoan.anaxa.common.util.Copying;

import java.util.Map;
import java.util.Objects;

/**
 * Request body for vector search.
 *
 * @param vector query vector
 * @param topK number of hits to return
 * @param filter optional payload filter
 */
public record SearchRequest(float[] vector, int topK, Map<String, Object> filter) {
    /**
     * Creates a search request.
     */
    public SearchRequest {
        vector = Copying.vector(Objects.requireNonNull(vector, "vector"));
        if (vector.length == 0) {
            throw new IllegalArgumentException("Search vector must not be empty");
        }
        if (topK <= 0) {
            throw new IllegalArgumentException("topK must be positive");
        }
        filter = Copying.payload(filter);
    }
}
