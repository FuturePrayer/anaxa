package cn.suhoan.anaxa.common.model;

import java.util.Objects;

/**
 * Request body for restoring a collection from a backup.
 *
 * @param sourceCollection collection name inside the backup
 * @param collectionName target collection name
 */
public record RestoreCollectionRequest(String sourceCollection, String collectionName) {
    /**
     * Creates a restore request.
     */
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
