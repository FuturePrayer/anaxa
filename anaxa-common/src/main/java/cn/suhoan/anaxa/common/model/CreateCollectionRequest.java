package cn.suhoan.anaxa.common.model;

import java.util.Objects;

/**
 * Request body for creating a collection.
 *
 * @param name collection name
 * @param dimension vector dimension
 * @param metric similarity metric, defaults to cosine when null
 * @param flushThresholdBytes optional flush threshold override
 */
public record CreateCollectionRequest(String name, int dimension, MetricType metric, Long flushThresholdBytes) {
    /**
     * Creates a create-collection request.
     */
    public CreateCollectionRequest {
        name = Objects.requireNonNull(name, "name");
    }

    /**
     * Converts this request to a definition in the default tenant.
     *
     * @param defaultFlushThresholdBytes fallback flush threshold
     * @return validated collection definition
     */
    public CollectionDefinition toDefinition(long defaultFlushThresholdBytes) {
        return toDefinition(CollectionDefinition.DEFAULT_TENANT, defaultFlushThresholdBytes);
    }

    /**
     * Converts this request to a definition for a tenant.
     *
     * @param tenantId tenant id
     * @param defaultFlushThresholdBytes fallback flush threshold
     * @return validated collection definition
     */
    public CollectionDefinition toDefinition(String tenantId, long defaultFlushThresholdBytes) {
        long flushThreshold = flushThresholdBytes == null ? defaultFlushThresholdBytes : flushThresholdBytes;
        return new CollectionDefinition(name, dimension, metric, flushThreshold, tenantId);
    }
}
