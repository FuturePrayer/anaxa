package cn.suhoan.anaxa.index;

import cn.suhoan.anaxa.common.model.MetricType;

public interface SearchableVectors {
    String sourceId();

    int dimension();

    MetricType metric();

    int size();

    void scan(VectorEntryConsumer consumer);
}
