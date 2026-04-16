package cn.suhoan.anaxa.common.model;

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

    public CollectionStats {
        tenantId = CollectionDefinition.normalizeTenantId(tenantId);
        if (storageBytes < 0L) {
            throw new IllegalArgumentException("storageBytes must not be negative");
        }
    }
}
