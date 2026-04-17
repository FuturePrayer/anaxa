package cn.suhoan.anaxa.index;

import cn.suhoan.anaxa.common.model.MetricType;
import cn.suhoan.anaxa.common.model.SearchHit;
import cn.suhoan.anaxa.common.model.SearchRequest;

import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;

public final class FlatSegmentIndexSearcher implements SegmentIndexSearcher {
    private static final long PREFETCH_FLOOR_BYTES = 8L * 1024L * 1024L;
    private static final long PREFETCH_CEILING_BYTES = 64L * 1024L * 1024L;

    @Override
    public SourceSearchResult search(SearchableVectors source, SearchRequest request, BiPredicate<String, Long> isLiveEntry) {
        source.prefetch(Math.min(
                PREFETCH_CEILING_BYTES,
                Math.max(PREFETCH_FLOOR_BYTES, (long) source.dimension() * Math.max(1, request.topK()) * Float.BYTES * 128L)
        ));
        ArrayList<FlatVectorRef> vectors = new ArrayList<>(source.size());
        ArrayList<Map<String, Object>> payloads = new ArrayList<>(source.size());
        source.scan((id, sequence, tombstone, norm, vectorSegment, vectorOffsetBytes, payload) -> {
            if (tombstone) {
                return;
            }
            vectors.add(new FlatVectorRef(id, sequence, norm, vectorSegment, vectorOffsetBytes, payload));
            payloads.add(payload);
        });

        PayloadFilterIndex payloadIndex = PayloadFilterIndex.build(payloads);
        PayloadColumnStore payloadColumnStore = PayloadColumnStore.build(payloads);
        PayloadFilterPlan filterPlan = PayloadFilterPlan.compile(request.filter(), payloadIndex, payloadColumnStore);
        BitSet filtered = filterPlan.candidateOrdinals();
        int filterCandidateCount = filtered == null ? vectors.size() : filtered.cardinality();
        if (filtered != null && filtered.isEmpty()) {
            return new SourceSearchResult(
                    List.of(),
                    new SourceSearchMetrics(SearchMode.EXACT, vectors.size(), 0, 0, 0, 0, 0, false)
            );
        }

        float[] query = request.vector();
        float queryNorm = source.metric() == MetricType.COSINE ? VectorMetricScorer.norm(query) : 0.0F;
        TopKAccumulator accumulator = new TopKAccumulator(request.topK());
        int scoredCandidateCount = 0;
        if (filtered == null) {
            for (int ordinal = 0; ordinal < vectors.size(); ordinal++) {
                scoredCandidateCount += offer(accumulator, vectors.get(ordinal), source.metric(), query, queryNorm, isLiveEntry, filterPlan);
            }
        } else {
            for (int ordinal = filtered.nextSetBit(0); ordinal >= 0; ordinal = filtered.nextSetBit(ordinal + 1)) {
                scoredCandidateCount += offer(accumulator, vectors.get(ordinal), source.metric(), query, queryNorm, isLiveEntry, filterPlan);
            }
        }

        return new SourceSearchResult(
                accumulator.toSortedList(),
                new SourceSearchMetrics(SearchMode.EXACT, vectors.size(), filterCandidateCount, 0, 0, scoredCandidateCount, 0, false)
        );
    }

    private int offer(
            TopKAccumulator accumulator,
            FlatVectorRef vector,
            MetricType metric,
            float[] query,
            float queryNorm,
            BiPredicate<String, Long> isLiveEntry,
            PayloadFilterPlan filterPlan
    ) {
        if (!isLiveEntry.test(vector.id(), vector.sequence()) || !filterPlan.matches(vector.payload())) {
            return 0;
        }
        float score = VectorMetricScorer.score(
                metric,
                query,
                queryNorm,
                vector.vectorSegment(),
                vector.vectorOffsetBytes(),
                vector.norm()
        );
        accumulator.offer(new SearchHit(vector.id(), score, vector.payload(), vector.sequence()));
        return 1;
    }

    private record FlatVectorRef(
            String id,
            long sequence,
            float norm,
            MemorySegment vectorSegment,
            long vectorOffsetBytes,
            Map<String, Object> payload
    ) {
    }
}
