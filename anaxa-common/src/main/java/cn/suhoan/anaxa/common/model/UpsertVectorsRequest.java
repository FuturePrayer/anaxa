package cn.suhoan.anaxa.common.model;

import cn.suhoan.anaxa.common.util.Copying;

import java.util.List;
import java.util.Objects;

/**
 * Request body for upserting vectors.
 *
 * @param vectors vectors to upsert
 */
public record UpsertVectorsRequest(List<UpsertVector> vectors) {
    /**
     * Creates an upsert request.
     */
    public UpsertVectorsRequest {
        vectors = Copying.immutableList(Objects.requireNonNull(vectors, "vectors"));
        if (vectors.isEmpty()) {
            throw new IllegalArgumentException("vectors must not be empty");
        }
    }
}
