package cn.suhoan.anaxa.index;

import cn.suhoan.anaxa.common.model.MetricType;
import cn.suhoan.anaxa.common.model.SearchRequest;
import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HnswPqSegmentIndexSearcherTest {
    @Test
    void fallsBackToExactForSmallFilteredCandidateSetsAndPrefetches() {
        float[] target = targetVector();

        try (HnswPqSegmentIndexSearcher searcher = new HnswPqSegmentIndexSearcher()) {
            TestSource source = filteredSource(target);
            SourceSearchResult result = searcher.search(
                    source,
                    new SearchRequest(target, 5, Map.of("group", "blue")),
                    (id, sequence) -> true
            );

            assertEquals(SearchMode.EXACT, result.metrics().mode());
            assertFalse(result.hits().isEmpty());
            assertEquals("target", result.hits().getFirst().id());
            assertTrue(source.prefetchCalls() > 0);
            assertTrue(source.lastPrefetchBudgetBytes() > 0L);
        }
    }

    @Test
    void adaptsToApproximateSearchAndUsesSourceIndexCacheOnRepeatedQueries() {
        float[] target = targetVector();

        try (HnswPqSegmentIndexSearcher searcher = new HnswPqSegmentIndexSearcher()) {
            TestSource source = approximateSource(target);
            SearchRequest request = new SearchRequest(target, 10, Map.of());

            SourceSearchResult first = searcher.search(source, request, (id, sequence) -> true);
            SourceSearchResult second = searcher.search(source, request, (id, sequence) -> true);

            assertEquals(SearchMode.APPROXIMATE, first.metrics().mode());
            assertFalse(first.metrics().indexCacheHit());
            assertTrue(first.metrics().approximateCandidateCount() > 0);
            assertTrue(first.metrics().rerankedCandidateCount() >= request.topK());
            assertFalse(first.hits().isEmpty());

            assertEquals(SearchMode.APPROXIMATE, second.metrics().mode());
            assertTrue(second.metrics().indexCacheHit());
            assertEquals(
                    first.hits().stream().map(hit -> hit.id()).toList(),
                    second.hits().stream().map(hit -> hit.id()).toList()
            );
            assertTrue(source.prefetchCalls() >= 2);
        }
    }

    private static float[] targetVector() {
        float[] target = new float[16];
        target[1] = 0.8F;
        target[7] = 1.0F;
        target[9] = 0.5F;
        target[13] = 0.35F;
        return target;
    }

    private static TestSource filteredSource(float[] target) {
        ArrayList<TestVector> vectors = new ArrayList<>();
        for (int index = 0; index < 400; index++) {
            float[] vector = new float[16];
            vector[index % vector.length] = 1.0F;
            vector[(index + 3) % vector.length] = 0.25F;
            String group = index % 5 == 0 ? "blue" : "red";
            vectors.add(new TestVector("doc-" + index, index + 1L, vector, Map.of("group", group)));
        }
        vectors.add(new TestVector("target", 1_000L, target, Map.of("group", "blue")));
        return new TestSource("filtered-source", MetricType.COSINE, 16, vectors);
    }

    private static TestSource approximateSource(float[] target) {
        ArrayList<TestVector> vectors = new ArrayList<>();
        for (int index = 0; index < 700; index++) {
            float[] vector = new float[16];
            vector[index % vector.length] = 1.0F;
            vector[(index + 3) % vector.length] = 0.25F;
            vectors.add(new TestVector("doc-" + index, index + 1L, vector, Map.of("bucket", index % 16)));
        }
        vectors.add(new TestVector("target", 2_000L, target, Map.of("bucket", 9)));
        return new TestSource("approximate-source", MetricType.COSINE, 16, vectors);
    }

    private record TestVector(String id, long sequence, float[] vector, Map<String, Object> payload) {
        private float norm() {
            return VectorMetricScorer.norm(vector);
        }
    }

    private static final class TestSource implements SearchableVectors {
        private final String sourceId;
        private final MetricType metric;
        private final int dimension;
        private final List<TestVector> vectors;
        private final long approximateBytes;
        private final AtomicInteger prefetchCalls;
        private volatile long lastPrefetchBudgetBytes;

        private TestSource(String sourceId, MetricType metric, int dimension, List<TestVector> vectors) {
            this.sourceId = sourceId;
            this.metric = metric;
            this.dimension = dimension;
            this.vectors = List.copyOf(vectors);
            this.approximateBytes = (long) vectors.size() * dimension * Float.BYTES * 2L;
            this.prefetchCalls = new AtomicInteger();
            this.lastPrefetchBudgetBytes = 0L;
        }

        @Override
        public String sourceId() {
            return sourceId;
        }

        @Override
        public long searchStateVersion() {
            return 1L;
        }

        @Override
        public int dimension() {
            return dimension;
        }

        @Override
        public MetricType metric() {
            return metric;
        }

        @Override
        public int size() {
            return vectors.size();
        }

        @Override
        public long approximateBytes() {
            return approximateBytes;
        }

        @Override
        public void prefetch(long budgetBytes) {
            prefetchCalls.incrementAndGet();
            lastPrefetchBudgetBytes = budgetBytes;
        }

        @Override
        public void scan(VectorEntryConsumer consumer) {
            for (TestVector vector : vectors) {
                consumer.accept(
                        vector.id(),
                        vector.sequence(),
                        false,
                        vector.norm(),
                        MemorySegment.ofArray(vector.vector()),
                        0L,
                        vector.payload()
                );
            }
        }

        private int prefetchCalls() {
            return prefetchCalls.get();
        }

        private long lastPrefetchBudgetBytes() {
            return lastPrefetchBudgetBytes;
        }
    }
}
