package cn.suhoan.anaxa.index;

public record SourceSearchMetrics(
        SearchMode mode,
        int totalVectors,
        int filterCandidateCount,
        int approximateCandidateCount,
        int rerankedCandidateCount,
        int scoredCandidateCount,
        int graphVisitedCount,
        boolean indexCacheHit
) {
}
