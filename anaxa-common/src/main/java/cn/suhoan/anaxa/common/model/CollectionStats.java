package cn.suhoan.anaxa.common.model;

public record CollectionStats(
        String name,
        int dimension,
        MetricType metric,
        long liveVectorCount,
        int segmentCount,
        boolean flushInProgress
) {
}
