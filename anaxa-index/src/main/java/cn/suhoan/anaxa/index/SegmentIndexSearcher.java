package cn.suhoan.anaxa.index;

import cn.suhoan.anaxa.common.model.SearchRequest;

import java.util.function.BiPredicate;

public interface SegmentIndexSearcher extends AutoCloseable {
    SourceSearchResult search(SearchableVectors source, SearchRequest request, BiPredicate<String, Long> isLiveEntry);

    default void warm(SearchableVectors source) {
    }

    default void evict(String sourceId) {
    }

    @Override
    default void close() {
    }
}
