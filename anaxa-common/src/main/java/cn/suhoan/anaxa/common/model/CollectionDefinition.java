package cn.suhoan.anaxa.common.model;

import java.util.Objects;
import java.util.regex.Pattern;

public record CollectionDefinition(String name, int dimension, MetricType metric, long flushThresholdBytes) {
    private static final Pattern NAME_PATTERN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,127}");

    public CollectionDefinition {
        name = Objects.requireNonNull(name, "name").trim();
        if (!NAME_PATTERN.matcher(name).matches()) {
            throw new IllegalArgumentException("Collection name must match [A-Za-z0-9][A-Za-z0-9_-]{0,127}");
        }
        if (dimension <= 0) {
            throw new IllegalArgumentException("Collection dimension must be positive");
        }
        metric = metric == null ? MetricType.COSINE : metric;
        if (flushThresholdBytes <= 0) {
            throw new IllegalArgumentException("Collection flush threshold must be positive");
        }
    }
}
