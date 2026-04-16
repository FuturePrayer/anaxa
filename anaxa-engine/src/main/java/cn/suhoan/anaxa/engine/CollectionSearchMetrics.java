package cn.suhoan.anaxa.engine;

public record CollectionSearchMetrics(
        String tenantId,
        String collectionName,
        int sourceCount,
        int exactSourceCount,
        int approximateSourceCount,
        long filterCandidateCount,
        long approximateCandidateCount,
        long rerankedCandidateCount,
        long scoredCandidateCount,
        long graphVisitedCount,
        long sourceIndexCacheHitCount,
        long sourceIndexCacheMissCount,
        boolean queryCacheHit,
        int resultCount,
        long durationNanos
) {
}
