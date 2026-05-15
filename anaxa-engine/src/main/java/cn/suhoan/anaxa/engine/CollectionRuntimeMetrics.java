package cn.suhoan.anaxa.engine;

public record CollectionRuntimeMetrics(
        String tenantId,
        String collectionName,
        int activeSearches,
        int activeForegroundSearches,
        int pendingFlushMemTables,
        int queuedWarmTasks,
        int adaptiveSourceSearchLimit,
        long residentSourceBytes,
        int residentSourceCount,
        boolean flushInProgress,
        boolean compactionInProgress
) {
}
