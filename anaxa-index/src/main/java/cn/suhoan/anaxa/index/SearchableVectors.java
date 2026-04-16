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

    void scan(VectorEntryConsumer consumer);
}
