package cn.suhoan.anaxa.common.model;

import cn.suhoan.anaxa.common.util.Copying;

import java.util.Map;
import java.util.Objects;

public record UpsertVector(String id, float[] vector, Map<String, Object> payload) {
    public UpsertVector {
        id = Objects.requireNonNull(id, "id").trim();
        if (id.isEmpty()) {
            throw new IllegalArgumentException("Vector id must not be blank");
        }
        vector = Copying.vector(Objects.requireNonNull(vector, "vector"));
        if (vector.length == 0) {
            throw new IllegalArgumentException("Vector must not be empty");
        }
        payload = Copying.payload(payload);
    }
}
