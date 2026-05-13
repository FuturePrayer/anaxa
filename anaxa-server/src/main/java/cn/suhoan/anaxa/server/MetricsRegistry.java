package cn.suhoan.anaxa.server;

import cn.suhoan.anaxa.common.model.CollectionStats;
import cn.suhoan.anaxa.engine.CollectionSearchMetrics;
import cn.suhoan.anaxa.engine.CompactionMetrics;
import cn.suhoan.anaxa.engine.EngineObserver;
import cn.suhoan.anaxa.engine.FlushMetrics;
import cn.suhoan.anaxa.engine.VectorDatabaseEngine;

import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Predicate;

final class MetricsRegistry implements EngineObserver {
    private static final double[] REQUEST_DURATION_BUCKETS = {0.001D, 0.005D, 0.01D, 0.05D, 0.1D, 0.25D, 0.5D, 1.0D, 2.5D, 5.0D};

    private final ConcurrentHashMap<HttpMetricKey, LongAdder> requestCounts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<RouteMetricKey, LongAdder> requestDurationNanos = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<RouteMetricKey, LongAdder> requestDurationCount = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<RouteMetricKey, LongAdder[]> requestDurationBuckets = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<CollectionMetricKey, LongAdder> searchQueries = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<CollectionMetricKey, LongAdder> searchSlowQueries = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<CollectionMetricKey, LongAdder> searchHits = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<CollectionMetricKey, LongAdder> searchDurationNanos = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<CollectionMetricKey, LongAdder> searchDurationCount = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<CollectionMetricKey, LongAdder> flushCounts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<CollectionMetricKey, LongAdder> flushEntries = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<CollectionMetricKey, LongAdder> flushInputBytes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<CollectionMetricKey, LongAdder> flushOutputBytes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<CollectionMetricKey, LongAdder> flushDurationNanos = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<CollectionMetricKey, LongAdder> flushDurationCount = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<CollectionMetricKey, LongAdder> compactionCounts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<CollectionMetricKey, LongAdder> compactionInputSegments = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<CollectionMetricKey, LongAdder> compactionOutputSegments = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<CollectionMetricKey, LongAdder> compactionInputBytes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<CollectionMetricKey, LongAdder> compactionOutputBytes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<CollectionMetricKey, LongAdder> compactionDurationNanos = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<CollectionMetricKey, LongAdder> compactionDurationCount = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<CollectionMetricKey, LongAdder> snapshotCounts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<CollectionMetricKey, LongAdder> snapshotFailures = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<CollectionMetricKey, LongAdder> snapshotAutomaticRuns = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<CollectionMetricKey, LongAdder> snapshotManualRuns = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<CollectionMetricKey, LongAdder> snapshotRetentionDeletes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<CollectionMetricKey, LongAdder> snapshotDurationNanos = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<CollectionMetricKey, LongAdder> snapshotDurationCount = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<CollectionMetricKey, LongAdder> searchSourceCounts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<ModeMetricKey, LongAdder> searchSourceModeCounts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<CollectionMetricKey, LongAdder> searchFilterCandidates = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<CollectionMetricKey, LongAdder> searchApproximateCandidates = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<CollectionMetricKey, LongAdder> searchRerankedCandidates = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<CollectionMetricKey, LongAdder> searchScoredCandidates = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<CollectionMetricKey, LongAdder> searchGraphVisited = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<CollectionMetricKey, LongAdder> searchSourceIndexCacheHits = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<CollectionMetricKey, LongAdder> searchSourceIndexCacheMisses = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<CollectionMetricKey, LongAdder> searchQueryCacheHits = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<CollectionMetricKey, LongAdder> searchQueryCacheMisses = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<CollectionMetricKey, LongAdder> searchResults = new ConcurrentHashMap<>();

    private final LongAdder authFailures = new LongAdder();
    private final LongAdder authorizationDenied = new LongAdder();
    private final LongAdder rateLimitedRequests = new LongAdder();
    private final LongAdder overloadRejectedRequests = new LongAdder();

    void recordRequest(String method, String route, int statusCode, long durationNanos) {
        requestCounts.computeIfAbsent(new HttpMetricKey(method, route, statusCode), ignored -> new LongAdder()).increment();
        RouteMetricKey routeKey = new RouteMetricKey(method, route);
        requestDurationNanos.computeIfAbsent(routeKey, ignored -> new LongAdder()).add(durationNanos);
        requestDurationCount.computeIfAbsent(routeKey, ignored -> new LongAdder()).increment();

        double seconds = durationNanos / 1_000_000_000.0D;
        LongAdder[] buckets = requestDurationBuckets.computeIfAbsent(routeKey, ignored -> newBucketAdders());
        for (int index = 0; index < REQUEST_DURATION_BUCKETS.length; index++) {
            if (seconds <= REQUEST_DURATION_BUCKETS[index]) {
                buckets[index].increment();
            }
        }
        buckets[REQUEST_DURATION_BUCKETS.length].increment();
    }

    void recordAuthFailure() {
        authFailures.increment();
    }

    void recordAuthorizationDenied() {
        authorizationDenied.increment();
    }

    void recordRateLimited() {
        rateLimitedRequests.increment();
    }

    void recordOverloadRejected() {
        overloadRejectedRequests.increment();
    }

    void recordSnapshot(String tenantId, String collectionName, long durationNanos, boolean success, boolean automatic) {
        CollectionMetricKey key = new CollectionMetricKey(tenantId, collectionName);
        if (success) {
            snapshotCounts.computeIfAbsent(key, ignored -> new LongAdder()).increment();
        } else {
            snapshotFailures.computeIfAbsent(key, ignored -> new LongAdder()).increment();
        }
        if (automatic) {
            snapshotAutomaticRuns.computeIfAbsent(key, ignored -> new LongAdder()).increment();
        } else {
            snapshotManualRuns.computeIfAbsent(key, ignored -> new LongAdder()).increment();
        }
        snapshotDurationNanos.computeIfAbsent(key, ignored -> new LongAdder()).add(durationNanos);
        snapshotDurationCount.computeIfAbsent(key, ignored -> new LongAdder()).increment();
    }

    void recordSnapshotRetentionDelete(String tenantId, String collectionName) {
        snapshotRetentionDeletes.computeIfAbsent(new CollectionMetricKey(tenantId, collectionName), ignored -> new LongAdder())
                .increment();
    }

    void recordSearch(String tenantId, String collectionName, int hitCount, long durationNanos, boolean slowQuery) {
        CollectionMetricKey key = new CollectionMetricKey(tenantId, collectionName);
        searchQueries.computeIfAbsent(key, ignored -> new LongAdder()).increment();
        if (slowQuery) {
            searchSlowQueries.computeIfAbsent(key, ignored -> new LongAdder()).increment();
        }
        searchHits.computeIfAbsent(key, ignored -> new LongAdder()).add(hitCount);
        searchDurationNanos.computeIfAbsent(key, ignored -> new LongAdder()).add(durationNanos);
        searchDurationCount.computeIfAbsent(key, ignored -> new LongAdder()).increment();
    }

    @Override
    public void onFlushCompleted(FlushMetrics metrics) {
        CollectionMetricKey key = new CollectionMetricKey(metrics.tenantId(), metrics.collectionName());
        flushCounts.computeIfAbsent(key, ignored -> new LongAdder()).increment();
        flushEntries.computeIfAbsent(key, ignored -> new LongAdder()).add(metrics.entryCount());
        flushInputBytes.computeIfAbsent(key, ignored -> new LongAdder()).add(metrics.inputBytes());
        flushOutputBytes.computeIfAbsent(key, ignored -> new LongAdder()).add(metrics.outputBytes());
        flushDurationNanos.computeIfAbsent(key, ignored -> new LongAdder()).add(metrics.durationNanos());
        flushDurationCount.computeIfAbsent(key, ignored -> new LongAdder()).increment();
    }

    @Override
    public void onCompactionCompleted(CompactionMetrics metrics) {
        CollectionMetricKey key = new CollectionMetricKey(metrics.tenantId(), metrics.collectionName());
        compactionCounts.computeIfAbsent(key, ignored -> new LongAdder()).increment();
        compactionInputSegments.computeIfAbsent(key, ignored -> new LongAdder()).add(metrics.inputSegments());
        compactionOutputSegments.computeIfAbsent(key, ignored -> new LongAdder()).add(metrics.outputSegments());
        compactionInputBytes.computeIfAbsent(key, ignored -> new LongAdder()).add(metrics.inputBytes());
        compactionOutputBytes.computeIfAbsent(key, ignored -> new LongAdder()).add(metrics.outputBytes());
        compactionDurationNanos.computeIfAbsent(key, ignored -> new LongAdder()).add(metrics.durationNanos());
        compactionDurationCount.computeIfAbsent(key, ignored -> new LongAdder()).increment();
    }

    @Override
    public void onSearchCompleted(CollectionSearchMetrics metrics) {
        CollectionMetricKey key = new CollectionMetricKey(metrics.tenantId(), metrics.collectionName());
        searchSourceCounts.computeIfAbsent(key, ignored -> new LongAdder()).add(metrics.sourceCount());
        searchSourceModeCounts.computeIfAbsent(new ModeMetricKey(metrics.tenantId(), metrics.collectionName(), "exact"), ignored -> new LongAdder())
                .add(metrics.exactSourceCount());
        searchSourceModeCounts.computeIfAbsent(new ModeMetricKey(metrics.tenantId(), metrics.collectionName(), "approximate"), ignored -> new LongAdder())
                .add(metrics.approximateSourceCount());
        searchFilterCandidates.computeIfAbsent(key, ignored -> new LongAdder()).add(metrics.filterCandidateCount());
        searchApproximateCandidates.computeIfAbsent(key, ignored -> new LongAdder()).add(metrics.approximateCandidateCount());
        searchRerankedCandidates.computeIfAbsent(key, ignored -> new LongAdder()).add(metrics.rerankedCandidateCount());
        searchScoredCandidates.computeIfAbsent(key, ignored -> new LongAdder()).add(metrics.scoredCandidateCount());
        searchGraphVisited.computeIfAbsent(key, ignored -> new LongAdder()).add(metrics.graphVisitedCount());
        searchSourceIndexCacheHits.computeIfAbsent(key, ignored -> new LongAdder()).add(metrics.sourceIndexCacheHitCount());
        searchSourceIndexCacheMisses.computeIfAbsent(key, ignored -> new LongAdder()).add(metrics.sourceIndexCacheMissCount());
        searchResults.computeIfAbsent(key, ignored -> new LongAdder()).add(metrics.resultCount());
        if (metrics.queryCacheHit()) {
            searchQueryCacheHits.computeIfAbsent(key, ignored -> new LongAdder()).increment();
        } else {
            searchQueryCacheMisses.computeIfAbsent(key, ignored -> new LongAdder()).increment();
        }
    }

    String scrape(VectorDatabaseEngine engine) {
        return scrape(engine, null);
    }

    String scrape(VectorDatabaseEngine engine, String tenantScope) {
        Predicate<CollectionMetricKey> collectionFilter = key -> tenantScope == null || key.tenantId().equals(tenantScope);
        Predicate<ModeMetricKey> modeFilter = key -> tenantScope == null || key.tenantId().equals(tenantScope);

        StringBuilder builder = new StringBuilder(8192);
        builder.append("# TYPE anaxa_http_requests_total counter\n");
        requestCounts.entrySet().stream()
                .sorted(Comparator.comparing(entry -> entry.getKey().method() + entry.getKey().route() + entry.getKey().statusCode()))
                .forEach(entry -> builder.append("anaxa_http_requests_total")
                        .append(labels(Map.of(
                                "method", entry.getKey().method(),
                                "route", entry.getKey().route(),
                                "status", Integer.toString(entry.getKey().statusCode())
                        )))
                        .append(' ')
                        .append(entry.getValue().sum())
                        .append('\n'));

        builder.append("# TYPE anaxa_http_request_duration_seconds histogram\n");
        requestDurationBuckets.entrySet().stream()
                .sorted(Comparator.comparing(entry -> entry.getKey().method() + entry.getKey().route()))
                .forEach(entry -> {
                    for (int index = 0; index < REQUEST_DURATION_BUCKETS.length; index++) {
                        builder.append("anaxa_http_request_duration_seconds_bucket")
                                .append(labels(Map.of(
                                        "method", entry.getKey().method(),
                                        "route", entry.getKey().route(),
                                        "le", Double.toString(REQUEST_DURATION_BUCKETS[index])
                                )))
                                .append(' ')
                                .append(entry.getValue()[index].sum())
                                .append('\n');
                    }
                    builder.append("anaxa_http_request_duration_seconds_bucket")
                            .append(labels(Map.of(
                                    "method", entry.getKey().method(),
                                    "route", entry.getKey().route(),
                                    "le", "+Inf"
                            )))
                            .append(' ')
                            .append(entry.getValue()[REQUEST_DURATION_BUCKETS.length].sum())
                            .append('\n');
                });

        builder.append("# TYPE anaxa_http_request_duration_seconds_sum counter\n");
        requestDurationNanos.entrySet().stream()
                .sorted(Comparator.comparing(entry -> entry.getKey().method() + entry.getKey().route()))
                .forEach(entry -> builder.append("anaxa_http_request_duration_seconds_sum")
                        .append(labels(Map.of(
                                "method", entry.getKey().method(),
                                "route", entry.getKey().route()
                        )))
                        .append(' ')
                        .append(entry.getValue().sum() / 1_000_000_000.0D)
                        .append('\n'));

        builder.append("# TYPE anaxa_http_request_duration_seconds_count counter\n");
        requestDurationCount.entrySet().stream()
                .sorted(Comparator.comparing(entry -> entry.getKey().method() + entry.getKey().route()))
                .forEach(entry -> builder.append("anaxa_http_request_duration_seconds_count")
                        .append(labels(Map.of(
                                "method", entry.getKey().method(),
                                "route", entry.getKey().route()
                        )))
                        .append(' ')
                        .append(entry.getValue().sum())
                        .append('\n'));

        builder.append("# TYPE anaxa_http_auth_failures_total counter\n");
        builder.append("anaxa_http_auth_failures_total ").append(authFailures.sum()).append('\n');
        builder.append("# TYPE anaxa_http_authorization_denied_total counter\n");
        builder.append("anaxa_http_authorization_denied_total ").append(authorizationDenied.sum()).append('\n');
        builder.append("# TYPE anaxa_http_rate_limited_total counter\n");
        builder.append("anaxa_http_rate_limited_total ").append(rateLimitedRequests.sum()).append('\n');
        builder.append("# TYPE anaxa_http_overload_rejections_total counter\n");
        builder.append("anaxa_http_overload_rejections_total ").append(overloadRejectedRequests.sum()).append('\n');

        appendCollectionCounters(builder, "anaxa_search_queries_total", searchQueries, collectionFilter);
        appendCollectionCounters(builder, "anaxa_search_slow_queries_total", searchSlowQueries, collectionFilter);
        appendCollectionCounters(builder, "anaxa_search_hits_total", searchHits, collectionFilter);
        appendCollectionDuration(builder, "anaxa_search_duration_seconds", searchDurationNanos, searchDurationCount, collectionFilter);

        appendCollectionCounters(builder, "anaxa_engine_flush_total", flushCounts, collectionFilter);
        appendCollectionCounters(builder, "anaxa_engine_flush_entries_total", flushEntries, collectionFilter);
        appendCollectionCounters(builder, "anaxa_engine_flush_input_bytes_total", flushInputBytes, collectionFilter);
        appendCollectionCounters(builder, "anaxa_engine_flush_output_bytes_total", flushOutputBytes, collectionFilter);
        appendCollectionDuration(builder, "anaxa_engine_flush_duration_seconds", flushDurationNanos, flushDurationCount, collectionFilter);

        appendCollectionCounters(builder, "anaxa_engine_compaction_total", compactionCounts, collectionFilter);
        appendCollectionCounters(builder, "anaxa_engine_compaction_input_segments_total", compactionInputSegments, collectionFilter);
        appendCollectionCounters(builder, "anaxa_engine_compaction_output_segments_total", compactionOutputSegments, collectionFilter);
        appendCollectionCounters(builder, "anaxa_engine_compaction_input_bytes_total", compactionInputBytes, collectionFilter);
        appendCollectionCounters(builder, "anaxa_engine_compaction_output_bytes_total", compactionOutputBytes, collectionFilter);
        appendCollectionDuration(builder, "anaxa_engine_compaction_duration_seconds", compactionDurationNanos, compactionDurationCount, collectionFilter);

        appendCollectionCounters(builder, "anaxa_lifecycle_snapshot_total", snapshotCounts, collectionFilter);
        appendCollectionCounters(builder, "anaxa_lifecycle_snapshot_failures_total", snapshotFailures, collectionFilter);
        appendCollectionCounters(builder, "anaxa_lifecycle_snapshot_automatic_total", snapshotAutomaticRuns, collectionFilter);
        appendCollectionCounters(builder, "anaxa_lifecycle_snapshot_manual_total", snapshotManualRuns, collectionFilter);
        appendCollectionCounters(builder, "anaxa_lifecycle_snapshot_retention_deletes_total", snapshotRetentionDeletes, collectionFilter);
        appendCollectionDuration(builder, "anaxa_lifecycle_snapshot_duration_seconds", snapshotDurationNanos, snapshotDurationCount, collectionFilter);

        appendCollectionCounters(builder, "anaxa_search_sources_total", searchSourceCounts, collectionFilter);
        appendModeCounters(builder, "anaxa_search_source_queries_total", searchSourceModeCounts, modeFilter);
        appendCollectionCounters(builder, "anaxa_search_filter_candidates_total", searchFilterCandidates, collectionFilter);
        appendCollectionCounters(builder, "anaxa_search_approximate_candidates_total", searchApproximateCandidates, collectionFilter);
        appendCollectionCounters(builder, "anaxa_search_reranked_candidates_total", searchRerankedCandidates, collectionFilter);
        appendCollectionCounters(builder, "anaxa_search_scored_candidates_total", searchScoredCandidates, collectionFilter);
        appendCollectionCounters(builder, "anaxa_search_graph_visited_total", searchGraphVisited, collectionFilter);
        appendCollectionCounters(builder, "anaxa_search_source_index_cache_hits_total", searchSourceIndexCacheHits, collectionFilter);
        appendCollectionCounters(builder, "anaxa_search_source_index_cache_misses_total", searchSourceIndexCacheMisses, collectionFilter);
        appendCollectionCounters(builder, "anaxa_search_query_cache_hits_total", searchQueryCacheHits, collectionFilter);
        appendCollectionCounters(builder, "anaxa_search_query_cache_misses_total", searchQueryCacheMisses, collectionFilter);
        appendCollectionCounters(builder, "anaxa_search_results_total", searchResults, collectionFilter);

        var collections = tenantScope == null ? engine.listCollections() : engine.listCollections(tenantScope);
        long liveVectors = collections.stream().mapToLong(CollectionStats::liveVectorCount).sum();
        long tombstones = collections.stream().mapToLong(CollectionStats::tombstoneCount).sum();
        long segments = collections.stream().mapToInt(CollectionStats::segmentCount).sum();
        long storageBytes = collections.stream().mapToLong(CollectionStats::storageBytes).sum();

        builder.append("# TYPE anaxa_engine_collections gauge\n");
        builder.append("anaxa_engine_collections ").append(collections.size()).append('\n');
        builder.append("# TYPE anaxa_engine_live_vectors gauge\n");
        builder.append("anaxa_engine_live_vectors ").append(liveVectors).append('\n');
        builder.append("# TYPE anaxa_engine_tombstones gauge\n");
        builder.append("anaxa_engine_tombstones ").append(tombstones).append('\n');
        builder.append("# TYPE anaxa_engine_segments gauge\n");
        builder.append("anaxa_engine_segments ").append(segments).append('\n');
        builder.append("# TYPE anaxa_engine_storage_bytes gauge\n");
        builder.append("anaxa_engine_storage_bytes ").append(storageBytes).append('\n');

        builder.append("# TYPE anaxa_engine_collection_live_vectors gauge\n");
        collections.stream()
                .sorted(Comparator.comparing(CollectionStats::tenantId).thenComparing(CollectionStats::name))
                .forEach(stats -> builder.append("anaxa_engine_collection_live_vectors")
                        .append(labels(statsLabels(stats)))
                        .append(' ')
                        .append(stats.liveVectorCount())
                        .append('\n'));

        builder.append("# TYPE anaxa_engine_collection_tombstones gauge\n");
        collections.stream()
                .sorted(Comparator.comparing(CollectionStats::tenantId).thenComparing(CollectionStats::name))
                .forEach(stats -> builder.append("anaxa_engine_collection_tombstones")
                        .append(labels(statsLabels(stats)))
                        .append(' ')
                        .append(stats.tombstoneCount())
                        .append('\n'));

        builder.append("# TYPE anaxa_engine_collection_segments gauge\n");
        collections.stream()
                .sorted(Comparator.comparing(CollectionStats::tenantId).thenComparing(CollectionStats::name))
                .forEach(stats -> builder.append("anaxa_engine_collection_segments")
                        .append(labels(statsLabels(stats)))
                        .append(' ')
                        .append(stats.segmentCount())
                        .append('\n'));

        builder.append("# TYPE anaxa_engine_collection_storage_bytes gauge\n");
        collections.stream()
                .sorted(Comparator.comparing(CollectionStats::tenantId).thenComparing(CollectionStats::name))
                .forEach(stats -> builder.append("anaxa_engine_collection_storage_bytes")
                        .append(labels(statsLabels(stats)))
                        .append(' ')
                        .append(stats.storageBytes())
                        .append('\n'));

        return builder.toString();
    }

    private static void appendCollectionCounters(
            StringBuilder builder,
            String metricName,
            ConcurrentHashMap<CollectionMetricKey, LongAdder> counters,
            Predicate<CollectionMetricKey> filter
    ) {
        builder.append("# TYPE ").append(metricName).append(" counter\n");
        counters.entrySet().stream()
                .filter(entry -> filter.test(entry.getKey()))
                .sorted(Comparator.comparing(entry -> entry.getKey().tenantId() + "/" + entry.getKey().collectionName()))
                .forEach(entry -> builder.append(metricName)
                        .append(labels(Map.of(
                                "tenant", entry.getKey().tenantId(),
                                "collection", entry.getKey().collectionName()
                        )))
                        .append(' ')
                        .append(entry.getValue().sum())
                        .append('\n'));
    }

    private static void appendModeCounters(
            StringBuilder builder,
            String metricName,
            ConcurrentHashMap<ModeMetricKey, LongAdder> counters,
            Predicate<ModeMetricKey> filter
    ) {
        builder.append("# TYPE ").append(metricName).append(" counter\n");
        counters.entrySet().stream()
                .filter(entry -> filter.test(entry.getKey()))
                .sorted(Comparator.comparing(entry ->
                        entry.getKey().tenantId() + "/" + entry.getKey().collectionName() + "/" + entry.getKey().mode()))
                .forEach(entry -> builder.append(metricName)
                        .append(labels(Map.of(
                                "tenant", entry.getKey().tenantId(),
                                "collection", entry.getKey().collectionName(),
                                "mode", entry.getKey().mode()
                        )))
                        .append(' ')
                        .append(entry.getValue().sum())
                        .append('\n'));
    }

    private static void appendCollectionDuration(
            StringBuilder builder,
            String metricName,
            ConcurrentHashMap<CollectionMetricKey, LongAdder> sums,
            ConcurrentHashMap<CollectionMetricKey, LongAdder> counts,
            Predicate<CollectionMetricKey> filter
    ) {
        builder.append("# TYPE ").append(metricName).append("_sum counter\n");
        sums.entrySet().stream()
                .filter(entry -> filter.test(entry.getKey()))
                .sorted(Comparator.comparing(entry -> entry.getKey().tenantId() + "/" + entry.getKey().collectionName()))
                .forEach(entry -> builder.append(metricName)
                        .append("_sum")
                        .append(labels(Map.of(
                                "tenant", entry.getKey().tenantId(),
                                "collection", entry.getKey().collectionName()
                        )))
                        .append(' ')
                        .append(entry.getValue().sum() / 1_000_000_000.0D)
                        .append('\n'));

        builder.append("# TYPE ").append(metricName).append("_count counter\n");
        counts.entrySet().stream()
                .filter(entry -> filter.test(entry.getKey()))
                .sorted(Comparator.comparing(entry -> entry.getKey().tenantId() + "/" + entry.getKey().collectionName()))
                .forEach(entry -> builder.append(metricName)
                        .append("_count")
                        .append(labels(Map.of(
                                "tenant", entry.getKey().tenantId(),
                                "collection", entry.getKey().collectionName()
                        )))
                        .append(' ')
                        .append(entry.getValue().sum())
                        .append('\n'));
    }

    private static Map<String, String> statsLabels(CollectionStats stats) {
        return Map.of("tenant", stats.tenantId(), "collection", stats.name());
    }

    private static LongAdder[] newBucketAdders() {
        LongAdder[] buckets = new LongAdder[REQUEST_DURATION_BUCKETS.length + 1];
        for (int index = 0; index < buckets.length; index++) {
            buckets[index] = new LongAdder();
        }
        return buckets;
    }

    private static String labels(Map<String, String> labels) {
        StringBuilder builder = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> entry : labels.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
            if (!first) {
                builder.append(',');
            }
            first = false;
            builder.append(entry.getKey())
                    .append("=\"")
                    .append(entry.getValue().replace("\\", "\\\\").replace("\"", "\\\""))
                    .append('"');
        }
        builder.append('}');
        return builder.toString();
    }

    private record HttpMetricKey(String method, String route, int statusCode) {
    }

    private record RouteMetricKey(String method, String route) {
    }

    private record CollectionMetricKey(String tenantId, String collectionName) {
    }

    private record ModeMetricKey(String tenantId, String collectionName, String mode) {
    }
}
