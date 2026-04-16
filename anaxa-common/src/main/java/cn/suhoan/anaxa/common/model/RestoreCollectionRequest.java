package cn.suhoan.anaxa.common.model;

import java.util.Objects;

public record RestoreCollectionRequest(String sourceCollection, String collectionName) {
    public RestoreCollectionRequest {
        sourceCollection = Objects.requireNonNull(sourceCollection, "sourceCollection").trim();
        collectionName = Objects.requireNonNull(collectionName, "collectionName").trim();
        if (sourceCollection.isEmpty()) {
            throw new IllegalArgumentException("sourceCollection must not be blank");
        }
        if (collectionName.isEmpty()) {
            throw new IllegalArgumentException("collectionName must not be blank");
        }
    }
}
