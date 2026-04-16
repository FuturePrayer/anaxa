package cn.suhoan.anaxa.engine;

public record FlushMetrics(
        String tenantId,
        String collectionName,
        int entryCount,
        long inputBytes,
        long outputBytes,
        long durationNanos
) {
}
