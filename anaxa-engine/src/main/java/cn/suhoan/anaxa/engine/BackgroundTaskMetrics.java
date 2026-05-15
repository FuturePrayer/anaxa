package cn.suhoan.anaxa.engine;

public record BackgroundTaskMetrics(
        String tenantId,
        String collectionName,
        String taskType,
        long queuedNanos,
        long yieldNanos,
        long durationNanos,
        boolean success
) {
}
