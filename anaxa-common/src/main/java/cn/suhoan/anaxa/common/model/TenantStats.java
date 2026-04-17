package cn.suhoan.anaxa.common.model;

import java.time.Instant;

public record TenantStats(
        String tenantId,
        int collectionCount,
        long liveVectorCount,
        long tombstoneCount,
        int segmentCount,
        long storageBytes,
        Integer maxCollections,
        Long maxLiveVectors,
        Long maxStorageBytes,
        Integer rateLimitPerMinute,
        Integer rateLimitBurst,
        int backupCount,
        Instant lastSnapshotAt
) {
    public TenantStats {
        tenantId = CollectionDefinition.normalizeTenantId(tenantId);
        if (collectionCount < 0) {
            throw new IllegalArgumentException("collectionCount must not be negative");
        }
        if (liveVectorCount < 0L) {
            throw new IllegalArgumentException("liveVectorCount must not be negative");
        }
        if (tombstoneCount < 0L) {
            throw new IllegalArgumentException("tombstoneCount must not be negative");
        }
        if (segmentCount < 0) {
            throw new IllegalArgumentException("segmentCount must not be negative");
        }
        if (storageBytes < 0L) {
            throw new IllegalArgumentException("storageBytes must not be negative");
        }
        if (maxCollections != null && maxCollections < 0) {
            throw new IllegalArgumentException("maxCollections must not be negative");
        }
        if (maxLiveVectors != null && maxLiveVectors < 0L) {
            throw new IllegalArgumentException("maxLiveVectors must not be negative");
        }
        if (maxStorageBytes != null && maxStorageBytes < 0L) {
            throw new IllegalArgumentException("maxStorageBytes must not be negative");
        }
        if (rateLimitPerMinute != null && rateLimitPerMinute < 0) {
            throw new IllegalArgumentException("rateLimitPerMinute must not be negative");
        }
        if (rateLimitBurst != null && rateLimitBurst < 0) {
            throw new IllegalArgumentException("rateLimitBurst must not be negative");
        }
        if (backupCount < 0) {
            throw new IllegalArgumentException("backupCount must not be negative");
        }
    }
}
