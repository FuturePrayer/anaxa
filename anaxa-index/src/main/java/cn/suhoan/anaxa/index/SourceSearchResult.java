package cn.suhoan.anaxa.index;

import cn.suhoan.anaxa.common.model.SearchHit;

import java.util.List;
import java.util.Objects;

public record SourceSearchResult(List<SearchHit> hits, SourceSearchMetrics metrics) {
    public SourceSearchResult {
        hits = List.copyOf(Objects.requireNonNull(hits, "hits"));
        metrics = Objects.requireNonNull(metrics, "metrics");
    }
}
