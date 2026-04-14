package cn.suhoan.anaxa.index;

import cn.suhoan.anaxa.common.model.SearchHit;
import cn.suhoan.anaxa.common.model.SearchRequest;

import java.util.List;
import java.util.function.BiPredicate;

public interface SegmentIndexSearcher {
    List<SearchHit> search(SearchableVectors source, SearchRequest request, BiPredicate<String, Long> isLiveEntry);
}
