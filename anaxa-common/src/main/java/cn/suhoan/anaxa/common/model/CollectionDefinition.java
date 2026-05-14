package cn.suhoan.anaxa.common.model;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Validated definition of an AnaxaDB collection.
 *
 * @param name collection name
 * @param dimension vector dimension
 * @param metric similarity metric
 * @param flushThresholdBytes flush threshold in bytes
 * @param tenantId tenant that owns the collection
 */
public record CollectionDefinition(String name, int dimension, MetricType metric, long flushThresholdBytes, String tenantId) {
    /** Default tenant id used when no tenant is supplied. */
    public static final String DEFAULT_TENANT = "default";

    private static final Pattern IDENTIFIER_PATTERN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,127}");

    /**
     * Creates a collection definition in the default tenant.
     *
     * @param name collection name
     * @param dimension vector dimension
     * @param metric similarity metric
     * @param flushThresholdBytes flush threshold in bytes
     */
    public CollectionDefinition(String name, int dimension, MetricType metric, long flushThresholdBytes) {
        this(name, dimension, metric, flushThresholdBytes, DEFAULT_TENANT);
    }

    /**
     * Creates and validates a collection definition.
     */
    public CollectionDefinition {
        name = Objects.requireNonNull(name, "name").trim();
        if (!IDENTIFIER_PATTERN.matcher(name).matches()) {
            throw new IllegalArgumentException("Collection name must match [A-Za-z0-9][A-Za-z0-9_-]{0,127}");
        }
        if (dimension <= 0) {
            throw new IllegalArgumentException("Collection dimension must be positive");
        }
        metric = metric == null ? MetricType.COSINE : metric;
        if (flushThresholdBytes <= 0) {
            throw new IllegalArgumentException("Collection flush threshold must be positive");
        }
        tenantId = normalizeTenantId(tenantId);
    }

    /**
     * Trims and validates a tenant id, using the default tenant when null.
     *
     * @param tenantId tenant id to normalize
     * @return normalized tenant id
     */
    public static String normalizeTenantId(String tenantId) {
        String normalized = tenantId == null ? DEFAULT_TENANT : tenantId.trim();
        if (!IDENTIFIER_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException("Tenant id must match [A-Za-z0-9][A-Za-z0-9_-]{0,127}");
        }
        return normalized;
    }
}
