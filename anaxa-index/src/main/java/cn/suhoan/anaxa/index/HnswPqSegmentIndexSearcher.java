package cn.suhoan.anaxa.index;

import cn.suhoan.anaxa.common.model.MetricType;
import cn.suhoan.anaxa.common.model.SearchHit;
import cn.suhoan.anaxa.common.model.SearchRequest;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiPredicate;

public final class HnswPqSegmentIndexSearcher implements SegmentIndexSearcher {
    private static final int EXACT_SCAN_THRESHOLD_BASE = 256;
    private static final int FILTER_EXACT_SCAN_THRESHOLD_BASE = 512;
    private static final int EF_SEARCH_BASE = 64;
    private static final int EF_SEARCH_CEILING = 768;
    private static final int RERANK_WINDOW_FLOOR = 24;
    private static final int RERANK_WINDOW_CEILING = 384;
    private static final int MAX_SUBSPACES = 4;
    private static final int MAX_CENTROIDS = 16;
    private static final int HNSW_MAX_LEVEL = 8;
    private static final int HNSW_M = 8;
    private static final int HNSW_M0 = 16;
    private static final int HNSW_EF_CONSTRUCTION = 64;
    private static final double EXACT_SCAN_FILTER_RATIO = 0.12D;
    private static final long BUILD_PREFETCH_BUDGET_BYTES = 64L * 1024L * 1024L;
    private static final long QUERY_PREFETCH_FLOOR_BYTES = 8L * 1024L * 1024L;
    private static final long QUERY_PREFETCH_CEILING_BYTES = 64L * 1024L * 1024L;
    private static final long APPROXIMATE_PREFETCH_CEILING_BYTES = 32L * 1024L * 1024L;
    private static final int ARTIFACT_MAGIC = 0x414E4E31;
    private static final int ARTIFACT_VERSION = 2;
    private static final int ARTIFACT_FLAG_QUANTIZER = 1;
    private static final int ARTIFACT_FLAG_GRAPH = 1 << 1;
    private static final ValueLayout.OfFloat FLOAT_LAYOUT = ValueLayout.JAVA_FLOAT_UNALIGNED.withOrder(ByteOrder.nativeOrder());

    private final ConcurrentHashMap<String, CachedSourceIndex> cache;

    public HnswPqSegmentIndexSearcher() {
        this.cache = new ConcurrentHashMap<>();
    }

    @Override
    public SourceSearchResult search(SearchableVectors source, SearchRequest request, BiPredicate<String, Long> isLiveEntry) {
        AtomicBoolean indexCacheHit = new AtomicBoolean(false);
        CachedSourceIndex index = cache.compute(source.sourceId(), (sourceId, current) ->
                current != null && current.version() == source.searchStateVersion()
                        ? markCacheHit(current, indexCacheHit)
                        : CachedSourceIndex.build(source)
        );
        return index.search(source, request, isLiveEntry, indexCacheHit.get());
    }

    @Override
    public void warm(SearchableVectors source) {
        cache.compute(source.sourceId(), (sourceId, current) ->
                current != null && current.version() == source.searchStateVersion()
                        ? current
                        : CachedSourceIndex.build(source)
        );
    }

    @Override
    public void evict(String sourceId) {
        cache.remove(sourceId);
    }

    @Override
    public void close() {
        cache.clear();
    }

    private static CachedSourceIndex markCacheHit(CachedSourceIndex current, AtomicBoolean cacheHit) {
        cacheHit.set(true);
        return current;
    }

    private record CachedSourceIndex(
            long version,
            MetricType metric,
            int dimension,
            List<IndexedVectorRef> vectors,
            PayloadFilterIndex payloadIndex,
            PayloadColumnStore payloadColumnStore,
            ProductQuantizer quantizer,
            HnswGraph graph
    ) {
        private static CachedSourceIndex build(SearchableVectors source) {
            source.prefetch(BUILD_PREFETCH_BUDGET_BYTES);
            ArrayList<IndexedVectorRef> vectors = new ArrayList<>(source.size());
            ArrayList<Map<String, Object>> payloads = new ArrayList<>(source.size());

            source.scan((id, sequence, tombstone, norm, vectorSegment, vectorOffsetBytes, payload) -> {
                if (tombstone) {
                    return;
                }
                vectors.add(new IndexedVectorRef(id, sequence, norm, vectorSegment, vectorOffsetBytes, payload));
                payloads.add(payload);
            });

            Path artifactPath = source.searchArtifactPath();
            if (artifactPath != null && Files.isRegularFile(artifactPath)) {
                try {
                    return loadFromArtifact(source, vectors, artifactPath);
                } catch (IOException | RuntimeException exception) {
                    tryDelete(artifactPath);
                }
            }

            long vectorBytes = (long) source.dimension() * Float.BYTES;
            ArrayList<float[]> denseVectors = new ArrayList<>(vectors.size());
            for (IndexedVectorRef vector : vectors) {
                denseVectors.add(readVector(vector.vectorSegment(), vector.vectorOffsetBytes(), source.dimension(), vectorBytes));
            }

            PayloadFilterIndex payloadIndex = PayloadFilterIndex.build(payloads);
            PayloadColumnStore payloadColumnStore = PayloadColumnStore.build(payloads);
            ProductQuantizer quantizer = denseVectors.size() <= EXACT_SCAN_THRESHOLD_BASE
                    ? null
                    : ProductQuantizer.train(denseVectors, source.dimension());
            HnswGraph graph = denseVectors.size() <= EXACT_SCAN_THRESHOLD_BASE
                    ? null
                    : HnswGraph.build(denseVectors, source.metric());

            CachedSourceIndex cached = new CachedSourceIndex(
                    source.searchStateVersion(),
                    source.metric(),
                    source.dimension(),
                    List.copyOf(vectors),
                    payloadIndex,
                    payloadColumnStore,
                    quantizer,
                    graph
            );
            if (artifactPath != null) {
                persistArtifact(cached, artifactPath);
            }
            return cached;
        }

        private static CachedSourceIndex loadFromArtifact(
                SearchableVectors source,
                List<IndexedVectorRef> vectors,
                Path artifactPath
        ) throws IOException {
            try (DataInputStream input = new DataInputStream(new BufferedInputStream(Files.newInputStream(artifactPath)))) {
                if (input.readInt() != ARTIFACT_MAGIC) {
                    throw new IOException("Invalid ANN artifact header");
                }
                if (input.readInt() != ARTIFACT_VERSION) {
                    throw new IOException("Unsupported ANN artifact version");
                }
                long version = input.readLong();
                int dimension = input.readInt();
                int metricOrdinal = input.readInt();
                int vectorCount = input.readInt();
                int flags = input.readInt();

                if (version != source.searchStateVersion()) {
                    throw new IOException("ANN artifact version mismatch");
                }
                if (dimension != source.dimension()) {
                    throw new IOException("ANN artifact dimension mismatch");
                }
                if (metricOrdinal != source.metric().ordinal()) {
                    throw new IOException("ANN artifact metric mismatch");
                }
                if (vectorCount != vectors.size()) {
                    throw new IOException("ANN artifact vector count mismatch");
                }

                PayloadFilterIndex payloadIndex = PayloadFilterIndex.readFrom(input);
                if (payloadIndex.size() != vectors.size()) {
                    throw new IOException("ANN artifact filter index size mismatch");
                }
                PayloadColumnStore payloadColumnStore = PayloadColumnStore.readFrom(input);
                if (payloadColumnStore.size() != vectors.size()) {
                    throw new IOException("ANN artifact payload column store size mismatch");
                }
                ProductQuantizer quantizer = (flags & ARTIFACT_FLAG_QUANTIZER) == 0
                        ? null
                        : ProductQuantizer.readFrom(input, candidateNorms(vectors));
                HnswGraph graph = (flags & ARTIFACT_FLAG_GRAPH) == 0
                        ? null
                        : HnswGraph.readFrom(input);
                if (input.read() >= 0) {
                    throw new IOException("Unexpected trailing bytes in ANN artifact");
                }

                return new CachedSourceIndex(
                        version,
                        source.metric(),
                        source.dimension(),
                        List.copyOf(vectors),
                        payloadIndex,
                        payloadColumnStore,
                        quantizer,
                        graph
                );
            }
        }

        private static void persistArtifact(CachedSourceIndex index, Path artifactPath) {
            try {
                if (artifactPath.getParent() != null) {
                    Files.createDirectories(artifactPath.getParent());
                }
                Path tempPath = artifactPath.resolveSibling(artifactPath.getFileName().toString() + ".tmp");
                try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(tempPath)))) {
                    output.writeInt(ARTIFACT_MAGIC);
                    output.writeInt(ARTIFACT_VERSION);
                    output.writeLong(index.version());
                    output.writeInt(index.dimension());
                    output.writeInt(index.metric().ordinal());
                    output.writeInt(index.vectors().size());
                    int flags = 0;
                    if (index.quantizer() != null) {
                        flags |= ARTIFACT_FLAG_QUANTIZER;
                    }
                    if (index.graph() != null) {
                        flags |= ARTIFACT_FLAG_GRAPH;
                    }
                    output.writeInt(flags);
                    index.payloadIndex().writeTo(output);
                    index.payloadColumnStore().writeTo(output);
                    if (index.quantizer() != null) {
                        index.quantizer().writeTo(output);
                    }
                    if (index.graph() != null) {
                        index.graph().writeTo(output);
                    }
                }
                moveReplace(tempPath, artifactPath);
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        }

        private SourceSearchResult search(
                SearchableVectors source,
                SearchRequest request,
                BiPredicate<String, Long> isLiveEntry,
                boolean indexCacheHit
        ) {
            if (vectors.isEmpty()) {
                return new SourceSearchResult(
                        List.of(),
                        new SourceSearchMetrics(SearchMode.EXACT, 0, 0, 0, 0, 0, 0, indexCacheHit)
                );
            }

            float[] query = request.vector();
            float queryNorm = metric == MetricType.COSINE ? VectorMetricScorer.norm(query) : 0.0F;
            PayloadFilterPlan filterPlan = PayloadFilterPlan.compile(request.filter(), payloadIndex, payloadColumnStore);
            BitSet filtered = filterPlan.candidateOrdinals();
            int filterCandidateCount = filtered == null ? vectors.size() : filtered.cardinality();
            if (filtered != null && filtered.isEmpty()) {
                return new SourceSearchResult(
                        List.of(),
                        new SourceSearchMetrics(SearchMode.EXACT, vectors.size(), 0, 0, 0, 0, 0, indexCacheHit)
                );
            }

            AnnSearchPlan searchPlan = AnnSearchPlan.plan(
                    vectors.size(),
                    filterCandidateCount,
                    request.topK(),
                    dimension,
                    graph != null && quantizer != null
            );
            source.prefetch(searchPlan.prefetchBudgetBytes());

            if (searchPlan.exact()) {
                ExactScanResult exact = exactScan(request, isLiveEntry, filterPlan, filtered, queryNorm);
                return new SourceSearchResult(
                        exact.hits(),
                        new SourceSearchMetrics(
                                SearchMode.EXACT,
                                vectors.size(),
                                filterCandidateCount,
                                0,
                                0,
                                exact.scoredCandidateCount(),
                                0,
                                indexCacheHit
                        )
                );
            }

            QueryLookup lookup = quantizer.lookup(query, metric, queryNorm);
            GraphSearchResult graphSearchResult = graph.search(lookup, filtered, searchPlan.efSearch());
            if (graphSearchResult.ordinals().isEmpty()) {
                return new SourceSearchResult(
                        List.of(),
                        new SourceSearchMetrics(
                                SearchMode.APPROXIMATE,
                                vectors.size(),
                                filterCandidateCount,
                                0,
                                0,
                                0,
                                graphSearchResult.visitedCount(),
                                indexCacheHit
                        )
                );
            }

            TopKAccumulator accumulator = new TopKAccumulator(request.topK());
            int rerankLimit = Math.min(graphSearchResult.ordinals().size(), searchPlan.rerankLimit());
            int scoredCandidateCount = 0;
            for (int index = 0; index < rerankLimit; index++) {
                int ordinal = graphSearchResult.ordinals().get(index);
                IndexedVectorRef vector = vectors.get(ordinal);
                if (!isLiveEntry.test(vector.id(), vector.sequence())) {
                    continue;
                }
                if ((filtered != null && !filtered.get(ordinal)) || !filterPlan.matches(vector.payload())) {
                    continue;
                }

                float score = VectorMetricScorer.score(
                        metric,
                        query,
                        queryNorm,
                        vector.vectorSegment(),
                        vector.vectorOffsetBytes(),
                        vector.norm()
                );
                scoredCandidateCount++;
                accumulator.offer(new SearchHit(vector.id(), score, vector.payload(), vector.sequence()));
            }

            return new SourceSearchResult(
                    accumulator.toSortedList(),
                    new SourceSearchMetrics(
                            SearchMode.APPROXIMATE,
                            vectors.size(),
                            filterCandidateCount,
                            graphSearchResult.ordinals().size(),
                            rerankLimit,
                            scoredCandidateCount,
                            graphSearchResult.visitedCount(),
                            indexCacheHit
                    )
            );
        }

        private ExactScanResult exactScan(
                SearchRequest request,
                BiPredicate<String, Long> isLiveEntry,
                PayloadFilterPlan filterPlan,
                BitSet filtered,
                float queryNorm
        ) {
            TopKAccumulator accumulator = new TopKAccumulator(request.topK());
            int scoredCandidateCount = 0;
            if (filtered == null) {
                for (int ordinal = 0; ordinal < vectors.size(); ordinal++) {
                    scoredCandidateCount += offerExact(accumulator, request, isLiveEntry, filterPlan, ordinal, queryNorm);
                }
            } else {
                for (int ordinal = filtered.nextSetBit(0); ordinal >= 0; ordinal = filtered.nextSetBit(ordinal + 1)) {
                    scoredCandidateCount += offerExact(accumulator, request, isLiveEntry, filterPlan, ordinal, queryNorm);
                }
            }
            return new ExactScanResult(accumulator.toSortedList(), scoredCandidateCount);
        }

        private int offerExact(
                TopKAccumulator accumulator,
                SearchRequest request,
                BiPredicate<String, Long> isLiveEntry,
                PayloadFilterPlan filterPlan,
                int ordinal,
                float queryNorm
        ) {
            IndexedVectorRef vector = vectors.get(ordinal);
            if (!isLiveEntry.test(vector.id(), vector.sequence()) || !filterPlan.matches(vector.payload())) {
                return 0;
            }

            float score = VectorMetricScorer.score(
                    metric,
                    request.vector(),
                    queryNorm,
                    vector.vectorSegment(),
                    vector.vectorOffsetBytes(),
                    vector.norm()
            );
            accumulator.offer(new SearchHit(vector.id(), score, vector.payload(), vector.sequence()));
            return 1;
        }
    }

    private record ExactScanResult(List<SearchHit> hits, int scoredCandidateCount) {
    }

    private record AnnSearchPlan(boolean exact, int efSearch, int rerankLimit, long prefetchBudgetBytes) {
        private static AnnSearchPlan plan(
                int vectorCount,
                int filterCandidateCount,
                int topK,
                int dimension,
                boolean annAvailable
        ) {
            int safeTopK = Math.max(1, topK);
            int effectiveCandidates = Math.max(filterCandidateCount, safeTopK);
            int exactThreshold = Math.max(EXACT_SCAN_THRESHOLD_BASE, safeTopK * 32);
            int filteredExactThreshold = Math.max(FILTER_EXACT_SCAN_THRESHOLD_BASE, safeTopK * 40);
            double filterRatio = vectorCount == 0 ? 0D : effectiveCandidates / (double) vectorCount;

            boolean exact = !annAvailable
                    || vectorCount <= exactThreshold
                    || effectiveCandidates <= filteredExactThreshold
                    || filterRatio <= EXACT_SCAN_FILTER_RATIO;

            int adaptiveEf = Math.max(EF_SEARCH_BASE, safeTopK * 6);
            adaptiveEf += log2ceil(Math.max(1, vectorCount)) * 8;
            if (filterCandidateCount > 0 && filterCandidateCount < vectorCount) {
                adaptiveEf = Math.min(adaptiveEf, Math.max(safeTopK * 4, filterCandidateCount));
            }
            adaptiveEf = Math.max(safeTopK * 4, adaptiveEf);
            adaptiveEf = Math.min(EF_SEARCH_CEILING, adaptiveEf);

            int candidatePressure = Math.max(1, effectiveCandidates / safeTopK);
            int rerankMultiplier = Math.min(8, 2 + log2ceil(candidatePressure));
            int rerankLimit = Math.max(RERANK_WINDOW_FLOOR, safeTopK * rerankMultiplier);
            if (filterRatio <= 0.35D) {
                rerankLimit += safeTopK * 2;
            }
            rerankLimit = Math.max(safeTopK, Math.min(RERANK_WINDOW_CEILING, rerankLimit));

            long approximatePrefetch = Math.min(
                    APPROXIMATE_PREFETCH_CEILING_BYTES,
                    Math.max(
                            QUERY_PREFETCH_FLOOR_BYTES,
                            (long) safeTopK * Math.max(1, dimension) * Float.BYTES * 128L
                    )
            );
            return new AnnSearchPlan(
                    exact,
                    adaptiveEf,
                    rerankLimit,
                    exact ? QUERY_PREFETCH_CEILING_BYTES : approximatePrefetch
            );
        }
    }

    private record IndexedVectorRef(
            String id,
            long sequence,
            float norm,
            MemorySegment vectorSegment,
            long vectorOffsetBytes,
            Map<String, Object> payload
    ) {
    }

    private static int log2ceil(int value) {
        if (value <= 1) {
            return 0;
        }
        return Integer.SIZE - Integer.numberOfLeadingZeros(value - 1);
    }

    private record QueryLookup(
            MetricType metric,
            float queryNorm,
            float[][] centroidScores,
            byte[][] codes,
            float[] candidateNorms
    ) {
        private float score(int ordinal) {
            float sum = 0.0F;
            byte[] vectorCodes = codes[ordinal];
            for (int subspace = 0; subspace < vectorCodes.length; subspace++) {
                sum += centroidScores[subspace][Byte.toUnsignedInt(vectorCodes[subspace])];
            }
            if (metric == MetricType.COSINE) {
                float candidateNorm = candidateNorms[ordinal];
                return queryNorm == 0.0F || candidateNorm == 0.0F ? 0.0F : sum / (queryNorm * candidateNorm);
            }
            return -sum;
        }
    }

    private static final class ProductQuantizer {
        private final int[] offsets;
        private final int[] lengths;
        private final float[][][] centroids;
        private final byte[][] codes;
        private final float[] candidateNorms;

        private ProductQuantizer(int[] offsets, int[] lengths, float[][][] centroids, byte[][] codes, float[] candidateNorms) {
            this.offsets = offsets;
            this.lengths = lengths;
            this.centroids = centroids;
            this.codes = codes;
            this.candidateNorms = candidateNorms;
        }

        private static ProductQuantizer train(List<float[]> vectors, int dimension) {
            int subspaces = Math.min(MAX_SUBSPACES, Math.max(1, dimension));
            int[] offsets = new int[subspaces];
            int[] lengths = new int[subspaces];
            int baseLength = dimension / subspaces;
            int remainder = dimension % subspaces;
            int offset = 0;
            for (int subspace = 0; subspace < subspaces; subspace++) {
                offsets[subspace] = offset;
                lengths[subspace] = baseLength + (subspace < remainder ? 1 : 0);
                offset += lengths[subspace];
            }

            int centroidsPerSubspace = Math.min(MAX_CENTROIDS, Math.max(2, vectors.size()));
            float[][][] centroids = new float[subspaces][][];
            for (int subspace = 0; subspace < subspaces; subspace++) {
                centroids[subspace] = trainSubspace(vectors, offsets[subspace], lengths[subspace], centroidsPerSubspace);
            }

            byte[][] codes = new byte[vectors.size()][subspaces];
            float[] norms = new float[vectors.size()];
            for (int vectorIndex = 0; vectorIndex < vectors.size(); vectorIndex++) {
                float[] vector = vectors.get(vectorIndex);
                norms[vectorIndex] = VectorMetricScorer.norm(vector);
                for (int subspace = 0; subspace < subspaces; subspace++) {
                    codes[vectorIndex][subspace] = (byte) nearestCentroid(
                            vector,
                            offsets[subspace],
                            lengths[subspace],
                            centroids[subspace]
                    );
                }
            }

            return new ProductQuantizer(offsets, lengths, centroids, codes, norms);
        }

        private static ProductQuantizer readFrom(DataInput input, float[] candidateNorms) throws IOException {
            int subspaces = input.readInt();
            int[] offsets = new int[subspaces];
            int[] lengths = new int[subspaces];
            for (int index = 0; index < subspaces; index++) {
                offsets[index] = input.readInt();
            }
            for (int index = 0; index < subspaces; index++) {
                lengths[index] = input.readInt();
            }

            float[][][] centroids = new float[subspaces][][];
            for (int subspace = 0; subspace < subspaces; subspace++) {
                int centroidCount = input.readInt();
                centroids[subspace] = new float[centroidCount][lengths[subspace]];
                for (int centroid = 0; centroid < centroidCount; centroid++) {
                    for (int dimension = 0; dimension < lengths[subspace]; dimension++) {
                        centroids[subspace][centroid][dimension] = input.readFloat();
                    }
                }
            }

            int vectorCount = input.readInt();
            if (vectorCount != candidateNorms.length) {
                throw new IOException("ANN artifact quantizer vector count mismatch");
            }
            byte[][] codes = new byte[vectorCount][subspaces];
            for (int index = 0; index < vectorCount; index++) {
                input.readFully(codes[index]);
            }
            return new ProductQuantizer(offsets, lengths, centroids, codes, candidateNorms);
        }

        private void writeTo(DataOutput output) throws IOException {
            output.writeInt(offsets.length);
            for (int offset : offsets) {
                output.writeInt(offset);
            }
            for (int length : lengths) {
                output.writeInt(length);
            }
            for (int subspace = 0; subspace < centroids.length; subspace++) {
                output.writeInt(centroids[subspace].length);
                for (float[] centroid : centroids[subspace]) {
                    for (float value : centroid) {
                        output.writeFloat(value);
                    }
                }
            }
            output.writeInt(codes.length);
            for (byte[] code : codes) {
                output.write(code);
            }
        }

        private QueryLookup lookup(float[] query, MetricType metric, float queryNorm) {
            float[][] centroidScores = new float[offsets.length][];
            for (int subspace = 0; subspace < offsets.length; subspace++) {
                centroidScores[subspace] = new float[centroids[subspace].length];
                for (int centroidIndex = 0; centroidIndex < centroids[subspace].length; centroidIndex++) {
                    centroidScores[subspace][centroidIndex] = scoreSubspace(
                            metric,
                            query,
                            offsets[subspace],
                            lengths[subspace],
                            centroids[subspace][centroidIndex]
                    );
                }
            }
            return new QueryLookup(metric, queryNorm, centroidScores, codes, candidateNorms);
        }

        private static float[][] trainSubspace(List<float[]> vectors, int offset, int length, int centroidsPerSubspace) {
            float[][] centroids = new float[centroidsPerSubspace][length];
            for (int centroidIndex = 0; centroidIndex < centroidsPerSubspace; centroidIndex++) {
                float[] source = vectors.get((centroidIndex * vectors.size()) / centroidsPerSubspace);
                System.arraycopy(source, offset, centroids[centroidIndex], 0, length);
            }

            int[] assignments = new int[vectors.size()];
            for (int iteration = 0; iteration < 6; iteration++) {
                float[][] sums = new float[centroidsPerSubspace][length];
                int[] counts = new int[centroidsPerSubspace];
                for (int vectorIndex = 0; vectorIndex < vectors.size(); vectorIndex++) {
                    float[] vector = vectors.get(vectorIndex);
                    int centroidIndex = nearestCentroid(vector, offset, length, centroids);
                    assignments[vectorIndex] = centroidIndex;
                    counts[centroidIndex]++;
                    for (int dimension = 0; dimension < length; dimension++) {
                        sums[centroidIndex][dimension] += vector[offset + dimension];
                    }
                }

                for (int centroidIndex = 0; centroidIndex < centroidsPerSubspace; centroidIndex++) {
                    if (counts[centroidIndex] == 0) {
                        continue;
                    }
                    for (int dimension = 0; dimension < length; dimension++) {
                        centroids[centroidIndex][dimension] = sums[centroidIndex][dimension] / counts[centroidIndex];
                    }
                }
            }
            return centroids;
        }

        private static int nearestCentroid(float[] vector, int offset, int length, float[][] centroids) {
            float bestDistance = Float.POSITIVE_INFINITY;
            int bestIndex = 0;
            for (int centroidIndex = 0; centroidIndex < centroids.length; centroidIndex++) {
                float distance = 0.0F;
                for (int dimension = 0; dimension < length; dimension++) {
                    float delta = vector[offset + dimension] - centroids[centroidIndex][dimension];
                    distance += delta * delta;
                }
                if (distance < bestDistance) {
                    bestDistance = distance;
                    bestIndex = centroidIndex;
                }
            }
            return bestIndex;
        }

        private static float scoreSubspace(MetricType metric, float[] query, int offset, int length, float[] centroid) {
            float value = 0.0F;
            for (int dimension = 0; dimension < length; dimension++) {
                if (metric == MetricType.COSINE) {
                    value += query[offset + dimension] * centroid[dimension];
                } else {
                    float delta = query[offset + dimension] - centroid[dimension];
                    value += delta * delta;
                }
            }
            return value;
        }
    }

    private static final class HnswGraph {
        private final Node[] nodes;
        private final int entryPoint;
        private final int maxLevel;

        private HnswGraph(Node[] nodes, int entryPoint, int maxLevel) {
            this.nodes = nodes;
            this.entryPoint = entryPoint;
            this.maxLevel = maxLevel;
        }

        private static HnswGraph build(List<float[]> denseVectors, MetricType metric) {
            float[] norms = new float[denseVectors.size()];
            for (int index = 0; index < denseVectors.size(); index++) {
                norms[index] = VectorMetricScorer.norm(denseVectors.get(index));
            }

            Node[] nodes = new Node[denseVectors.size()];
            int entryPoint = -1;
            int maxLevel = 0;
            Random random = new Random(7_337_331L);
            for (int ordinal = 0; ordinal < denseVectors.size(); ordinal++) {
                int level = randomLevel(random);
                nodes[ordinal] = new Node(level);
                if (entryPoint < 0) {
                    entryPoint = ordinal;
                    maxLevel = level;
                    continue;
                }

                float[] query = denseVectors.get(ordinal);
                float queryNorm = norms[ordinal];
                int current = entryPoint;
                float currentScore = exactScore(metric, query, queryNorm, denseVectors.get(current), norms[current]);

                for (int graphLevel = maxLevel; graphLevel > level; graphLevel--) {
                    current = greedySearch(nodes, denseVectors, norms, metric, query, queryNorm, current, graphLevel, currentScore);
                    currentScore = exactScore(metric, query, queryNorm, denseVectors.get(current), norms[current]);
                }

                int maxSearchLevel = Math.min(level, maxLevel);
                for (int graphLevel = maxSearchLevel; graphLevel >= 0; graphLevel--) {
                    List<ScoredOrdinal> neighbors = searchLayer(
                            nodes,
                            metric,
                            denseVectors,
                            norms,
                            query,
                            queryNorm,
                            List.of(current),
                            HNSW_EF_CONSTRUCTION,
                            graphLevel
                    );
                    int neighborLimit = graphLevel == 0 ? HNSW_M0 : HNSW_M;
                    int[] selected = selectNeighbors(neighbors, neighborLimit);
                    nodes[ordinal].neighbors[graphLevel] = selected;
                    for (int neighbor : selected) {
                        connectBidirectional(nodes, denseVectors, norms, metric, ordinal, neighbor, graphLevel);
                    }
                    if (!neighbors.isEmpty()) {
                        current = neighbors.getFirst().ordinal();
                    }
                }

                if (level > maxLevel) {
                    entryPoint = ordinal;
                    maxLevel = level;
                }
            }

            return new HnswGraph(nodes, entryPoint, maxLevel);
        }

        private static HnswGraph readFrom(DataInput input) throws IOException {
            int entryPoint = input.readInt();
            int maxLevel = input.readInt();
            int nodeCount = input.readInt();
            Node[] nodes = new Node[nodeCount];
            for (int ordinal = 0; ordinal < nodeCount; ordinal++) {
                int level = input.readInt();
                int levelCount = input.readInt();
                if (levelCount != level + 1) {
                    throw new IOException("ANN artifact HNSW level mismatch");
                }
                int[][] neighbors = new int[levelCount][];
                for (int graphLevel = 0; graphLevel < levelCount; graphLevel++) {
                    int neighborCount = input.readInt();
                    neighbors[graphLevel] = new int[neighborCount];
                    for (int index = 0; index < neighborCount; index++) {
                        neighbors[graphLevel][index] = input.readInt();
                    }
                }
                nodes[ordinal] = new Node(level, neighbors);
            }
            return new HnswGraph(nodes, entryPoint, maxLevel);
        }

        private void writeTo(DataOutput output) throws IOException {
            output.writeInt(entryPoint);
            output.writeInt(maxLevel);
            output.writeInt(nodes.length);
            for (Node node : nodes) {
                output.writeInt(node.level());
                output.writeInt(node.neighbors().length);
                for (int[] levelNeighbors : node.neighbors()) {
                    output.writeInt(levelNeighbors.length);
                    for (int neighbor : levelNeighbors) {
                        output.writeInt(neighbor);
                    }
                }
            }
        }

        private GraphSearchResult search(QueryLookup lookup, BitSet filtered, int efSearch) {
            if (entryPoint < 0) {
                return new GraphSearchResult(List.of(), 0);
            }

            int current = entryPoint;
            float currentScore = lookup.score(current);
            for (int graphLevel = maxLevel; graphLevel > 0; graphLevel--) {
                boolean improved;
                do {
                    improved = false;
                    for (int neighbor : nodes[current].neighbors[graphLevel]) {
                        float score = lookup.score(neighbor);
                        if (score > currentScore) {
                            current = neighbor;
                            currentScore = score;
                            improved = true;
                        }
                    }
                } while (improved);
            }

            LayerSearchResult layerResult = searchLayerWithStats(nodes, lookup::score, List.of(current), efSearch, 0);
            if (layerResult.results().isEmpty()) {
                return new GraphSearchResult(List.of(), layerResult.visitedCount());
            }

            ArrayList<Integer> ordinals = new ArrayList<>(layerResult.results().size());
            for (ScoredOrdinal candidate : layerResult.results()) {
                if (filtered == null || filtered.get(candidate.ordinal())) {
                    ordinals.add(candidate.ordinal());
                }
            }
            return new GraphSearchResult(List.copyOf(ordinals), layerResult.visitedCount());
        }

        private static int greedySearch(
                Node[] nodes,
                List<float[]> denseVectors,
                float[] norms,
                MetricType metric,
                float[] query,
                float queryNorm,
                int current,
                int level,
                float currentScore
        ) {
            boolean improved;
            do {
                improved = false;
                for (int neighbor : nodes[current].neighbors[level]) {
                    float score = exactScore(metric, query, queryNorm, denseVectors.get(neighbor), norms[neighbor]);
                    if (score > currentScore) {
                        current = neighbor;
                        currentScore = score;
                        improved = true;
                    }
                }
            } while (improved);
            return current;
        }

        private static List<ScoredOrdinal> searchLayer(
                Node[] nodes,
                MetricType metric,
                List<float[]> denseVectors,
                float[] norms,
                float[] query,
                float queryNorm,
                Collection<Integer> entryPoints,
                int ef,
                int level
        ) {
            return searchLayer(nodes, ordinal -> exactScore(metric, query, queryNorm, denseVectors.get(ordinal), norms[ordinal]), entryPoints, ef, level);
        }

        private static List<ScoredOrdinal> searchLayer(
                Node[] nodes,
                QueryLookup lookup,
                Collection<Integer> entryPoints,
                int ef,
                int level
        ) {
            return searchLayerWithStats(nodes, lookup::score, entryPoints, ef, level).results();
        }

        private static List<ScoredOrdinal> searchLayer(
                Node[] nodes,
                OrdinalScorer scorer,
                Collection<Integer> entryPoints,
                int ef,
                int level
        ) {
            return searchLayerWithStats(nodes, scorer, entryPoints, ef, level).results();
        }

        private static LayerSearchResult searchLayerWithStats(
                Node[] nodes,
                OrdinalScorer scorer,
                Collection<Integer> entryPoints,
                int ef,
                int level
        ) {
            BitSet visited = new BitSet(nodes.length);
            PriorityQueue<ScoredOrdinal> candidates = new PriorityQueue<>(Comparator.comparingDouble(ScoredOrdinal::score).reversed());
            PriorityQueue<ScoredOrdinal> best = new PriorityQueue<>(Comparator.comparingDouble(ScoredOrdinal::score));

            for (int entryPoint : entryPoints) {
                if (entryPoint < 0 || visited.get(entryPoint)) {
                    continue;
                }
                visited.set(entryPoint);
                float score = scorer.score(entryPoint);
                ScoredOrdinal candidate = new ScoredOrdinal(entryPoint, score);
                candidates.offer(candidate);
                best.offer(candidate);
            }

            while (!candidates.isEmpty()) {
                ScoredOrdinal current = candidates.poll();
                ScoredOrdinal worstBest = best.peek();
                if (worstBest != null && best.size() >= ef && current.score() < worstBest.score()) {
                    break;
                }

                for (int neighbor : nodes[current.ordinal()].neighbors[level]) {
                    if (visited.get(neighbor)) {
                        continue;
                    }
                    visited.set(neighbor);
                    float score = scorer.score(neighbor);
                    if (best.size() < ef || score > best.peek().score()) {
                        ScoredOrdinal scoredNeighbor = new ScoredOrdinal(neighbor, score);
                        candidates.offer(scoredNeighbor);
                        best.offer(scoredNeighbor);
                        if (best.size() > ef) {
                            best.poll();
                        }
                    }
                }
            }

            ArrayList<ScoredOrdinal> result = new ArrayList<>(best);
            result.sort(Comparator.comparingDouble(ScoredOrdinal::score).reversed());
            return new LayerSearchResult(List.copyOf(result), visited.cardinality());
        }

        private static void connectBidirectional(
                Node[] nodes,
                List<float[]> denseVectors,
                float[] norms,
                MetricType metric,
                int left,
                int right,
                int level
        ) {
            addNeighbor(nodes, denseVectors, norms, metric, left, right, level);
            addNeighbor(nodes, denseVectors, norms, metric, right, left, level);
        }

        private static void addNeighbor(
                Node[] nodes,
                List<float[]> denseVectors,
                float[] norms,
                MetricType metric,
                int owner,
                int neighbor,
                int level
        ) {
            if (level > nodes[owner].level) {
                return;
            }
            int[] current = nodes[owner].neighbors[level];
            for (int existing : current) {
                if (existing == neighbor) {
                    return;
                }
            }

            int[] merged = java.util.Arrays.copyOf(current, current.length + 1);
            merged[current.length] = neighbor;
            int limit = level == 0 ? HNSW_M0 : HNSW_M;
            if (merged.length > limit) {
                float[] ownerVector = denseVectors.get(owner);
                float ownerNorm = norms[owner];
                ArrayList<ScoredOrdinal> scored = new ArrayList<>(merged.length);
                for (int candidate : merged) {
                    scored.add(new ScoredOrdinal(
                            candidate,
                            exactScore(metric, ownerVector, ownerNorm, denseVectors.get(candidate), norms[candidate])
                    ));
                }
                scored.sort(Comparator.comparingDouble(ScoredOrdinal::score).reversed());
                merged = new int[limit];
                for (int index = 0; index < limit; index++) {
                    merged[index] = scored.get(index).ordinal();
                }
            }
            nodes[owner].neighbors[level] = merged;
        }

        private static int[] selectNeighbors(List<ScoredOrdinal> candidates, int limit) {
            int size = Math.min(limit, candidates.size());
            int[] selected = new int[size];
            for (int index = 0; index < size; index++) {
                selected[index] = candidates.get(index).ordinal();
            }
            return selected;
        }

        private static int randomLevel(Random random) {
            int level = 0;
            while (level < HNSW_MAX_LEVEL - 1 && random.nextDouble() < 0.5D) {
                level++;
            }
            return level;
        }

        private static float exactScore(
                MetricType metric,
                float[] query,
                float queryNorm,
                float[] candidate,
                float candidateNorm
        ) {
            return VectorMetricScorer.score(metric, query, queryNorm, candidate, candidateNorm);
        }
    }

    private record GraphSearchResult(List<Integer> ordinals, int visitedCount) {
    }

    private record LayerSearchResult(List<ScoredOrdinal> results, int visitedCount) {
    }

    private record Node(int level, int[][] neighbors) {
        private Node(int level) {
            this(level, initialize(level));
        }

        private static int[][] initialize(int level) {
            int[][] neighbors = new int[level + 1][];
            for (int index = 0; index <= level; index++) {
                neighbors[index] = new int[0];
            }
            return neighbors;
        }
    }

    private record ScoredOrdinal(int ordinal, float score) {
    }

    @FunctionalInterface
    private interface OrdinalScorer {
        float score(int ordinal);
    }

    private static float[] candidateNorms(List<IndexedVectorRef> vectors) {
        float[] norms = new float[vectors.size()];
        for (int index = 0; index < vectors.size(); index++) {
            norms[index] = vectors.get(index).norm();
        }
        return norms;
    }

    private static void moveReplace(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void tryDelete(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
        }
    }

    private static float[] readVector(MemorySegment vectorSegment, long vectorOffsetBytes, int dimension, long vectorBytes) {
        try {
            return vectorSegment.asSlice(vectorOffsetBytes, vectorBytes).toArray(ValueLayout.JAVA_FLOAT);
        } catch (IllegalArgumentException ignored) {
            float[] vector = new float[dimension];
            for (int index = 0; index < dimension; index++) {
                vector[index] = vectorSegment.get(FLOAT_LAYOUT, vectorOffsetBytes + (long) index * Float.BYTES);
            }
            return vector;
        }
    }
}
