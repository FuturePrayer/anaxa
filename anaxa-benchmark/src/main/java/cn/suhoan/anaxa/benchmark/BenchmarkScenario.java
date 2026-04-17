package cn.suhoan.anaxa.benchmark;

import cn.suhoan.anaxa.common.model.MetricType;

import java.util.Objects;

public record BenchmarkScenario(
        String name,
        String family,
        int dimension,
        MetricType metric,
        long flushThresholdBytes,
        int vectorCount,
        int ingestBatchSize,
        int warmupRequests,
        int searchRequests,
        int searchWorkers,
        int topK,
        int groupCount,
        boolean useFilter,
        PrepareMode prepareMode
) {
    public BenchmarkScenario {
        name = Objects.requireNonNull(name, "name");
        family = Objects.requireNonNull(family, "family");
        metric = Objects.requireNonNull(metric, "metric");
        prepareMode = Objects.requireNonNull(prepareMode, "prepareMode");
        if (dimension <= 0) {
            throw new IllegalArgumentException("dimension must be positive");
        }
        if (flushThresholdBytes <= 0L) {
            throw new IllegalArgumentException("flushThresholdBytes must be positive");
        }
        if (vectorCount <= 0) {
            throw new IllegalArgumentException("vectorCount must be positive");
        }
        if (ingestBatchSize <= 0) {
            throw new IllegalArgumentException("ingestBatchSize must be positive");
        }
        if (warmupRequests < 0) {
            throw new IllegalArgumentException("warmupRequests must not be negative");
        }
        if (searchRequests <= 0) {
            throw new IllegalArgumentException("searchRequests must be positive");
        }
        if (searchWorkers <= 0) {
            throw new IllegalArgumentException("searchWorkers must be positive");
        }
        if (topK <= 0) {
            throw new IllegalArgumentException("topK must be positive");
        }
        if (groupCount < 0) {
            throw new IllegalArgumentException("groupCount must not be negative");
        }
    }
}
