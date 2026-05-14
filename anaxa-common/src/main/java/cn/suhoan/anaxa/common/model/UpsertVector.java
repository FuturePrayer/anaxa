package cn.suhoan.anaxa.common.model;

import cn.suhoan.anaxa.common.util.Copying;

import java.util.Map;
import java.util.Objects;

/**
 * Vector and payload to insert or replace.
 *
 * @param id vector id
 * @param vector vector values
 * @param payload vector payload
 */
public record UpsertVector(String id, float[] vector, Map<String, Object> payload) {
    /**
     * Creates an upsert vector.
     */
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
