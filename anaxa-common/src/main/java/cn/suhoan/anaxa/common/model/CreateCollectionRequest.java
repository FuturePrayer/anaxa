package cn.suhoan.anaxa.common.model;

import java.util.Objects;

public record CreateCollectionRequest(String name, int dimension, MetricType metric, Long flushThresholdBytes) {
    public CreateCollectionRequest {
        name = Objects.requireNonNull(name, "name");
    }

    public CollectionDefinition toDefinition(long defaultFlushThresholdBytes) {
        long flushThreshold = flushThresholdBytes == null ? defaultFlushThresholdBytes : flushThresholdBytes;
        return new CollectionDefinition(name, dimension, metric, flushThreshold);
    }
}
