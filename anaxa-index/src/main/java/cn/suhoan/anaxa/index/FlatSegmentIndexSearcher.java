package cn.suhoan.anaxa.index;

import cn.suhoan.anaxa.common.model.MetricType;
import cn.suhoan.anaxa.common.model.SearchHit;
import cn.suhoan.anaxa.common.model.SearchRequest;

import java.util.Map;
import java.util.Objects;
import java.util.function.BiPredicate;

public final class FlatSegmentIndexSearcher implements SegmentIndexSearcher {
    @Override
    public java.util.List<SearchHit> search(SearchableVectors source, SearchRequest request, BiPredicate<String, Long> isLiveEntry) {
        float[] query = request.vector();
        float queryNorm = source.metric() == MetricType.COSINE ? VectorMetricScorer.norm(query) : 0.0F;
        TopKAccumulator accumulator = new TopKAccumulator(request.topK());

        source.scan((id, sequence, norm, vectorSegment, vectorOffsetBytes, payload) -> {
            if (!isLiveEntry.test(id, sequence) || !matchesFilter(payload, request.filter())) {
                return;
            }

            float score = VectorMetricScorer.score(
                    source.metric(),
                    query,
                    queryNorm,
                    vectorSegment,
                    vectorOffsetBytes,
                    norm
            );
            accumulator.offer(new SearchHit(id, score, payload, sequence));
        });

        return accumulator.toSortedList();
    }

    private boolean matchesFilter(Map<String, Object> payload, Map<String, Object> filter) {
        if (filter.isEmpty()) {
            return true;
        }
        for (Map.Entry<String, Object> entry : filter.entrySet()) {
            if (!Objects.equals(payload.get(entry.getKey()), entry.getValue())) {
                return false;
            }
        }
        return true;
    }
}
