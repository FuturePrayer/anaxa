package cn.suhoan.anaxa.index;

import cn.suhoan.anaxa.common.model.MetricType;

import java.nio.file.Path;

public interface SearchableVectors {
    String sourceId();

    long searchStateVersion();

    default Path searchArtifactPath() {
        return null;
    }

    int dimension();

    MetricType metric();

    int size();

    default long approximateBytes() {
        return 0L;
    }

    default void prefetch(long budgetBytes) {
    }

    void scan(VectorEntryConsumer consumer);
}
