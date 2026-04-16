package cn.suhoan.anaxa.engine;

public record CompactionMetrics(
        String tenantId,
        String collectionName,
        int inputSegments,
        int outputSegments,
        long inputBytes,
        long outputBytes,
        long durationNanos
) {
}
