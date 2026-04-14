package cn.suhoan.anaxa.common.model;

import cn.suhoan.anaxa.common.util.Copying;

import java.util.Map;
import java.util.Objects;

public record SearchRequest(float[] vector, int topK, Map<String, Object> filter) {
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
