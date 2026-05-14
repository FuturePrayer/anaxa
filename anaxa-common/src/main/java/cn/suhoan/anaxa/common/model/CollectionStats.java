package cn.suhoan.anaxa.common.model;

/**
 * Runtime statistics for a collection.
 *
 * @param name collection name
 * @param dimension vector dimension
 * @param metric similarity metric
 * @param liveVectorCount number of live vectors
 * @param tombstoneCount number of deleted vector markers
 * @param segmentCount number of immutable segments
 * @param flushInProgress whether a flush is currently running
 * @param compactionInProgress whether compaction is currently running
 * @param tenantId tenant that owns the collection
 * @param storageBytes estimated storage bytes used by this collection
 */
public record CollectionStats(
        String name,
        int dimension,
        MetricType metric,
        long liveVectorCount,
        long tombstoneCount,
        int segmentCount,
        boolean flushInProgress,
        boolean compactionInProgress,
        String tenantId,
        long storageBytes
) {
    /**
     * Creates collection statistics for the default tenant without storage usage.
     *
     * @param name collection name
     * @param dimension vector dimension
     * @param metric similarity metric
     * @param liveVectorCount number of live vectors
     * @param tombstoneCount number of deleted vector markers
     * @param segmentCount number of immutable segments
     * @param flushInProgress whether a flush is currently running
     * @param compactionInProgress whether compaction is currently running
     */
    public CollectionStats(
            String name,
            int dimension,
            MetricType metric,
            long liveVectorCount,
            long tombstoneCount,
            int segmentCount,
            boolean flushInProgress,
            boolean compactionInProgress
    ) {
        this(
                name,
                dimension,
                metric,
                liveVectorCount,
                tombstoneCount,
                segmentCount,
                flushInProgress,
                compactionInProgress,
                CollectionDefinition.DEFAULT_TENANT,
                0L
        );
    }

    /**
     * Creates collection statistics.
     */
    public CollectionStats {
        tenantId = CollectionDefinition.normalizeTenantId(tenantId);
        if (storageBytes < 0L) {
            throw new IllegalArgumentException("storageBytes must not be negative");
        }
    }
}
