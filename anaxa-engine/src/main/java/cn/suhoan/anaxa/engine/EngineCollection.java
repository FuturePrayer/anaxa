package cn.suhoan.anaxa.engine;

import cn.suhoan.anaxa.common.error.ValidationException;
import cn.suhoan.anaxa.common.json.JsonSupport;
import cn.suhoan.anaxa.common.model.CollectionDefinition;
import cn.suhoan.anaxa.common.model.CollectionStats;
import cn.suhoan.anaxa.common.model.SearchHit;
import cn.suhoan.anaxa.common.model.SearchRequest;
import cn.suhoan.anaxa.common.model.SearchResponse;
import cn.suhoan.anaxa.common.model.UpsertVector;
import cn.suhoan.anaxa.index.SearchMode;
import cn.suhoan.anaxa.index.SearchableVectors;
import cn.suhoan.anaxa.index.SegmentIndexSearcher;
import cn.suhoan.anaxa.index.SourceSearchResult;
import cn.suhoan.anaxa.index.TopKAccumulator;
import cn.suhoan.anaxa.storage.BufferedSegmentEntry;
import cn.suhoan.anaxa.storage.CollectionPaths;
import cn.suhoan.anaxa.storage.ImmutableSegment;
import cn.suhoan.anaxa.storage.MemTableEntry;
import cn.suhoan.anaxa.storage.OffHeapMemTable;
import cn.suhoan.anaxa.storage.SegmentEntry;
import cn.suhoan.anaxa.storage.SegmentWriter;
import cn.suhoan.anaxa.storage.WalAppender;
import cn.suhoan.anaxa.storage.WalRecord;
import cn.suhoan.anaxa.storage.WalReplay;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;

final class EngineCollection implements AutoCloseable {
    private static final int AUTO_COMPACTION_SEGMENT_THRESHOLD = 4;
    private static final Comparator<ImmutableSegment> SEGMENT_ORDER = Comparator.comparingLong(ImmutableSegment::generation);

    private final CollectionDefinition definition;
    private final CollectionPaths paths;
    private final SegmentIndexSearcher searcher;
    private final EngineObserver observer;
    private final AtomicLong sequenceGenerator;
    private final AtomicLong generationCounter;
    private final java.util.concurrent.ConcurrentHashMap<String, EntryState> latestStates;
    private final CopyOnWriteArrayList<ImmutableSegment> segments;
    private final CopyOnWriteArrayList<OffHeapMemTable> pendingFlushMemTables;
    private final ReentrantReadWriteLock stateLock;
    private final AtomicBoolean flushInProgress;
    private final AtomicBoolean compactionInProgress;
    private final AtomicBoolean closed;
    private final AtomicInteger activeSearches;
    private final ExecutorService flushExecutor;
    private final QueryCache queryCache;
    private final Object searchLifecycleMonitor;

    private volatile OffHeapMemTable activeMemTable;
    private volatile WalAppender activeWal;
    private volatile RuntimeException backgroundFailure;

    private EngineCollection(
            CollectionDefinition definition,
            CollectionPaths paths,
            SegmentIndexSearcher searcher,
            EngineObserver observer,
            AtomicLong sequenceGenerator,
            AtomicLong generationCounter,
            java.util.concurrent.ConcurrentHashMap<String, EntryState> latestStates,
            CopyOnWriteArrayList<ImmutableSegment> segments
    ) {
        this.definition = definition;
        this.paths = paths;
        this.searcher = searcher;
        this.observer = observer;
        this.sequenceGenerator = sequenceGenerator;
        this.generationCounter = generationCounter;
        this.latestStates = latestStates;
        this.segments = segments;
        this.pendingFlushMemTables = new CopyOnWriteArrayList<>();
        this.stateLock = new ReentrantReadWriteLock();
        this.flushInProgress = new AtomicBoolean(false);
        this.compactionInProgress = new AtomicBoolean(false);
        this.closed = new AtomicBoolean(false);
        this.activeSearches = new AtomicInteger(0);
        this.flushExecutor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("anaxa-flush-", 0).factory());
        this.queryCache = new QueryCache(128);
        this.searchLifecycleMonitor = new Object();
    }

    static EngineCollection createNew(
            CollectionDefinition definition,
            CollectionPaths paths,
            SegmentIndexSearcher searcher,
            EngineObserver observer
    )
            throws IOException {
        Files.createDirectories(paths.root());
        paths.ensureDirectories();
        Files.write(paths.metadataFile(), JsonSupport.writeBytes(definition));

        EngineCollection collection = new EngineCollection(
                definition,
                paths,
                searcher,
                observer,
                new AtomicLong(0L),
                new AtomicLong(1L),
                new java.util.concurrent.ConcurrentHashMap<>(),
                new CopyOnWriteArrayList<>()
        );
        collection.activeMemTable = new OffHeapMemTable(definition, 1L);
        collection.activeWal = WalAppender.open(paths.activeWal(), definition.dimension());
        return collection;
    }

    static EngineCollection openExisting(
            CollectionDefinition definition,
            CollectionPaths paths,
            SegmentIndexSearcher searcher,
            EngineObserver observer
    )
            throws IOException {
        paths.ensureDirectories();

        CopyOnWriteArrayList<ImmutableSegment> loadedSegments = new CopyOnWriteArrayList<>();
        java.util.concurrent.ConcurrentHashMap<String, EntryState> latestStates = new java.util.concurrent.ConcurrentHashMap<>();
        long maxSequence = 0L;
        long maxGeneration = 0L;

        for (Path segmentFile : paths.listSegmentFiles()) {
            try {
                ImmutableSegment segment = ImmutableSegment.load(segmentFile, definition);
                loadedSegments.add(segment);
                maxGeneration = Math.max(maxGeneration, segment.generation());
                for (SegmentEntry entry : segment.entries()) {
                    latestStates.merge(entry.id(), EntryState.of(entry.sequence(), entry.tombstone()), EngineCollection::newerState);
                    maxSequence = Math.max(maxSequence, entry.sequence());
                }
            } catch (IOException exception) {
                long generation = CollectionPaths.generationFromSegmentFile(segmentFile);
                Path recoverableWal = paths.frozenWal(generation);
                if (!Files.exists(recoverableWal)) {
                    throw new IOException("Failed to load segment " + segmentFile + " and no recoverable WAL exists", exception);
                }
                collectionQuarantine(paths, segmentFile, "segment-recovered");
                maxGeneration = Math.max(maxGeneration, generation);
            }
        }

        ArrayList<WalRecord> recoveredRecords = new ArrayList<>();
        ArrayList<Path> replayedWalFiles = new ArrayList<>();
        ArrayList<Path> walFilesToQuarantine = new ArrayList<>();
        boolean needsRewrite = false;
        for (Path walFile : paths.listWalFiles()) {
            String fileName = walFile.getFileName().toString();
            if (!"active.wal".equals(fileName)) {
                long walGeneration = CollectionPaths.generationFromWalFile(walFile);
                maxGeneration = Math.max(maxGeneration, walGeneration);
                if (Files.exists(paths.segmentFile(walGeneration))) {
                    Files.deleteIfExists(walFile);
                    continue;
                }
            }

            WalReplay.ReplayResult replayResult = WalReplay.readResult(walFile, definition);
            recoveredRecords.addAll(replayResult.records());
            replayedWalFiles.add(walFile);
            if (replayResult.recoveredTail()) {
                walFilesToQuarantine.add(walFile);
            }
            needsRewrite = needsRewrite
                    || replayResult.recoveredTail()
                    || replayResult.version() != WalAppender.CURRENT_VERSION
                    || !"active.wal".equals(fileName);
        }

        recoveredRecords.sort(Comparator.comparingLong(WalRecord::sequence));
        long activeGeneration = Math.max(1L, maxGeneration + 1L);
        EngineCollection collection = new EngineCollection(
                definition,
                paths,
                searcher,
                observer,
                new AtomicLong(maxSequence),
                new AtomicLong(activeGeneration),
                latestStates,
                loadedSegments
        );
        collection.activeMemTable = new OffHeapMemTable(definition, activeGeneration);

        for (WalRecord record : recoveredRecords) {
            if (record.tombstone()) {
                collection.activeMemTable.tombstone(record.id(), record.sequence());
            } else {
                collection.activeMemTable.upsert(record.id(), record.vector(), record.payload(), record.sequence());
            }
            collection.latestStates.merge(record.id(), EntryState.of(record.sequence(), record.tombstone()), EngineCollection::newerState);
            collection.sequenceGenerator.updateAndGet(current -> Math.max(current, record.sequence()));
        }

        if (needsRewrite) {
            for (Path walFile : walFilesToQuarantine) {
                collectionQuarantine(paths, walFile, "wal-recovered");
            }
            collection.rewriteActiveWalSnapshot();
            for (Path walFile : replayedWalFiles) {
                if (!walFile.equals(paths.activeWal())) {
                    Files.deleteIfExists(walFile);
                }
            }
        }

        collection.activeWal = WalAppender.open(paths.activeWal(), definition.dimension());
        return collection;
    }

    CollectionStats stats() {
        long liveVectorCount = 0L;
        long tombstoneCount = 0L;
        for (EntryState state : latestStates.values()) {
            if (state.tombstone()) {
                tombstoneCount++;
            } else {
                liveVectorCount++;
            }
        }
        return new CollectionStats(
                definition.name(),
                definition.dimension(),
                definition.metric(),
                liveVectorCount,
                tombstoneCount,
                segments.size(),
                flushInProgress.get() || !pendingFlushMemTables.isEmpty(),
                compactionInProgress.get(),
                definition.tenantId(),
                estimateStorageBytes()
        );
    }

    CollectionDefinition definition() {
        return definition;
    }

    long estimateAdditionalLiveVectors(List<String> ids) {
        ensureHealthy();
        long additional = 0L;
        for (String id : new LinkedHashSet<>(ids)) {
            EntryState state = latestStates.get(id);
            if (state == null || state.tombstone()) {
                additional++;
            }
        }
        return additional;
    }

    long estimateUpsertBytes(List<UpsertVector> vectors) {
        ensureHealthy();
        validateDimensions(vectors);
        long bytes = 0L;
        for (UpsertVector vector : vectors) {
            bytes += estimateFootprint(vector.id(), vector.payload(), vector.vector().length);
        }
        return bytes;
    }

    void upsert(List<UpsertVector> vectors) {
        ensureHealthy();
        stateLock.writeLock().lock();
        try {
            validateDimensions(vectors);
            List<WalRecord> walRecords = new ArrayList<>(vectors.size());
            for (UpsertVector vector : vectors) {
                long sequence = sequenceGenerator.incrementAndGet();
                walRecords.add(WalRecord.live(vector.id(), vector.vector(), vector.payload(), sequence));
            }

            activeWal.appendAll(walRecords);
            for (WalRecord record : walRecords) {
                activeMemTable.upsert(record.id(), record.vector(), record.payload(), record.sequence());
                latestStates.put(record.id(), EntryState.live(record.sequence()));
            }
        } catch (IOException exception) {
            throw new UncheckedIOException("Failed to append WAL for collection " + definition.name(), exception);
        } finally {
            stateLock.writeLock().unlock();
        }

        queryCache.clear();
        maybeScheduleFlush();
    }

    void delete(List<String> ids) {
        ensureHealthy();
        stateLock.writeLock().lock();
        try {
            LinkedHashSet<String> uniqueIds = new LinkedHashSet<>(ids);
            ArrayList<WalRecord> walRecords = new ArrayList<>(uniqueIds.size());
            for (String id : uniqueIds) {
                EntryState current = latestStates.get(id);
                if (current == null || current.tombstone()) {
                    continue;
                }
                long sequence = sequenceGenerator.incrementAndGet();
                walRecords.add(WalRecord.tombstone(id, sequence));
            }

            if (walRecords.isEmpty()) {
                return;
            }

            activeWal.appendAll(walRecords);
            for (WalRecord record : walRecords) {
                activeMemTable.tombstone(record.id(), record.sequence());
                latestStates.put(record.id(), EntryState.tombstone(record.sequence()));
            }
        } catch (IOException exception) {
            throw new UncheckedIOException("Failed to append delete tombstones for collection " + definition.name(), exception);
        } finally {
            stateLock.writeLock().unlock();
        }

        queryCache.clear();
        maybeScheduleFlush();
    }

    void compact() {
        ensureHealthy();
        if (!compactionInProgress.compareAndSet(false, true)) {
            throw new ValidationException("Compaction is already running for collection " + definition.name());
        }

        try {
            compactSegments();
            queryCache.clear();
        } catch (IOException exception) {
            throw new UncheckedIOException("Failed to compact collection " + definition.name(), exception);
        } finally {
            compactionInProgress.set(false);
        }
    }

    void flush() {
        ensureHealthy();
        scheduleFlush(true);
        awaitPendingFlushes();
    }

    SearchResponse search(SearchRequest request) {
        ensureHealthy();
        if (request.vector().length != definition.dimension()) {
            throw new ValidationException(
                    "Expected search dimension %d but got %d".formatted(definition.dimension(), request.vector().length)
            );
        }

        beginSearch();
        try {
            long startedAtNanos = System.nanoTime();
            SearchResponse cached = queryCache.get(request);
            if (cached != null) {
                observer.onSearchCompleted(new CollectionSearchMetrics(
                        definition.tenantId(),
                        definition.name(),
                        0,
                        0,
                        0,
                        0L,
                        0L,
                        0L,
                        0L,
                        0L,
                        0L,
                        0L,
                        true,
                        cached.hits().size(),
                        System.nanoTime() - startedAtNanos
                ));
                return cached;
            }

            List<SearchableVectors> sources = new ArrayList<>(1 + pendingFlushMemTables.size() + segments.size());
            stateLock.readLock().lock();
            try {
                sources.add(activeMemTable);
                sources.addAll(pendingFlushMemTables);
                sources.addAll(segments);
            } finally {
                stateLock.readLock().unlock();
            }

            try (StructuredTaskScope<SourceSearchResult, List<SourceSearchResult>> scope = StructuredTaskScope.open(
                    StructuredTaskScope.Joiner.<SourceSearchResult>allSuccessfulOrThrow())) {
                for (SearchableVectors source : sources) {
                    if (source.size() > 0) {
                        scope.fork(() -> searcher.search(source, request, this::isLiveEntry));
                    }
                }

                List<SourceSearchResult> partialResults = scope.join();
                TopKAccumulator accumulator = new TopKAccumulator(request.topK());
                int exactSourceCount = 0;
                int approximateSourceCount = 0;
                long filterCandidateCount = 0L;
                long approximateCandidateCount = 0L;
                long rerankedCandidateCount = 0L;
                long scoredCandidateCount = 0L;
                long graphVisitedCount = 0L;
                long sourceIndexCacheHitCount = 0L;
                long sourceIndexCacheMissCount = 0L;

                for (SourceSearchResult partialResult : partialResults) {
                    partialResult.hits().forEach(accumulator::offer);
                    if (partialResult.metrics().mode() == SearchMode.EXACT) {
                        exactSourceCount++;
                    } else {
                        approximateSourceCount++;
                    }
                    filterCandidateCount += partialResult.metrics().filterCandidateCount();
                    approximateCandidateCount += partialResult.metrics().approximateCandidateCount();
                    rerankedCandidateCount += partialResult.metrics().rerankedCandidateCount();
                    scoredCandidateCount += partialResult.metrics().scoredCandidateCount();
                    graphVisitedCount += partialResult.metrics().graphVisitedCount();
                    if (partialResult.metrics().indexCacheHit()) {
                        sourceIndexCacheHitCount++;
                    } else {
                        sourceIndexCacheMissCount++;
                    }
                }
                SearchResponse response = new SearchResponse(accumulator.toSortedList());
                queryCache.put(request, response);
                observer.onSearchCompleted(new CollectionSearchMetrics(
                        definition.tenantId(),
                        definition.name(),
                        partialResults.size(),
                        exactSourceCount,
                        approximateSourceCount,
                        filterCandidateCount,
                        approximateCandidateCount,
                        rerankedCandidateCount,
                        scoredCandidateCount,
                        graphVisitedCount,
                        sourceIndexCacheHitCount,
                        sourceIndexCacheMissCount,
                        false,
                        response.hits().size(),
                        System.nanoTime() - startedAtNanos
                ));
                return response;
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Search interrupted for collection " + definition.name(), exception);
            } catch (RuntimeException exception) {
                throw exception;
            } catch (Throwable throwable) {
                throw new RuntimeException("Search failed for collection " + definition.name(), throwable);
            }
        } finally {
            endSearch();
        }
    }

    void writeBackup(CollectionPaths backupPaths) throws IOException {
        ensureHealthy();
        stateLock.writeLock().lock();
        try {
            if (flushInProgress.get() || compactionInProgress.get() || !pendingFlushMemTables.isEmpty()) {
                throw new ValidationException(
                        "Collection " + definition.name() + " is not quiescent enough for backup; retry after flush/compaction completes"
                );
            }
            if (Files.exists(backupPaths.root())) {
                throw new ValidationException("Backup already exists at " + backupPaths.root());
            }

            try {
                backupPaths.ensureDirectories();
                Files.write(backupPaths.metadataFile(), JsonSupport.writeBytes(definition));
                for (ImmutableSegment segment : segments) {
                    Path targetSegment = backupPaths.segmentFile(segment.generation());
                    copyFile(segment.path(), targetSegment);
                    Path artifact = segment.searchArtifactPath();
                    if (artifact != null && Files.exists(artifact)) {
                        copyFile(artifact, targetSegment.resolveSibling(targetSegment.getFileName().toString() + ".ann"));
                    }
                }
                try (WalAppender wal = WalAppender.open(backupPaths.activeWal(), definition.dimension())) {
                    wal.appendAll(activeMemTable.snapshotEntries().stream().map(MemTableEntry::toWalRecord).toList());
                }
            } catch (IOException | RuntimeException exception) {
                try {
                    deleteRecursively(backupPaths.root());
                } catch (IOException cleanupException) {
                    exception.addSuppressed(cleanupException);
                }
                throw exception;
            }
        } finally {
            stateLock.writeLock().unlock();
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }

        queryCache.clear();

        RuntimeException failure = null;
        try {
            if (activeWal != null) {
                activeWal.close();
            }
        } catch (IOException exception) {
            failure = new UncheckedIOException("Failed to close WAL for collection " + definition.name(), exception);
        }

        flushExecutor.shutdown();
        try {
            if (!flushExecutor.awaitTermination(10L, TimeUnit.SECONDS)) {
                flushExecutor.shutdownNow();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            flushExecutor.shutdownNow();
            if (failure == null) {
                failure = new RuntimeException("Interrupted while closing collection " + definition.name(), exception);
            }
        }

        awaitActiveSearches();
        failure = closeQuietly(activeMemTable, failure);
        for (OffHeapMemTable memTable : pendingFlushMemTables) {
            failure = closeQuietly(memTable, failure);
        }
        for (ImmutableSegment segment : segments) {
            failure = closeQuietly(segment, failure);
        }

        if (failure != null) {
            throw failure;
        }
    }

    private void maybeScheduleFlush() {
        scheduleFlush(false);
    }

    private void scheduleFlush(boolean force) {
        if (backgroundFailure != null) {
            return;
        }
        if (!force && activeMemTable.approximateBytes() < definition.flushThresholdBytes()) {
            return;
        }
        if (!flushInProgress.compareAndSet(false, true)) {
            return;
        }

        OffHeapMemTable frozenMemTable;
        Path frozenWalPath;
        stateLock.writeLock().lock();
        try {
            if (backgroundFailure != null
                    || (!force && activeMemTable.approximateBytes() < definition.flushThresholdBytes())
                    || (force && activeMemTable.size() == 0)) {
                flushInProgress.set(false);
                return;
            }

            frozenMemTable = activeMemTable;
            frozenWalPath = paths.frozenWal(frozenMemTable.generation());

            activeWal.close();
            moveIntoPlace(paths.activeWal(), frozenWalPath);

            long nextGeneration = generationCounter.incrementAndGet();
            activeMemTable = new OffHeapMemTable(definition, nextGeneration);
            activeWal = WalAppender.open(paths.activeWal(), definition.dimension());
            pendingFlushMemTables.add(frozenMemTable);
        } catch (IOException exception) {
            flushInProgress.set(false);
            throw new UncheckedIOException("Failed to rotate WAL for collection " + definition.name(), exception);
        } finally {
            stateLock.writeLock().unlock();
        }

        flushExecutor.submit(() -> flushFrozenMemTable(frozenMemTable, frozenWalPath));
    }

    private void flushFrozenMemTable(OffHeapMemTable frozenMemTable, Path frozenWalPath) {
        long startedAtNanos = System.nanoTime();
        long inputBytes = frozenMemTable.approximateBytes();
        try {
            ImmutableSegment segment = SegmentWriter.write(paths, definition, frozenMemTable);
            if (segment != null) {
                searcher.warm(segment);
            }
            stateLock.writeLock().lock();
            try {
                if (segment != null) {
                    segments.add(segment);
                }
                pendingFlushMemTables.remove(frozenMemTable);
            } finally {
                stateLock.writeLock().unlock();
            }
            awaitActiveSearches();
            Files.deleteIfExists(frozenWalPath);
            searcher.evict(frozenMemTable.sourceId());
            frozenMemTable.close();
            queryCache.clear();
            observer.onFlushCompleted(new FlushMetrics(
                    definition.tenantId(),
                    definition.name(),
                    frozenMemTable.size(),
                    inputBytes,
                    segment == null ? 0L : fileSize(segment.path()) + fileSize(segment.searchArtifactPath()),
                    System.nanoTime() - startedAtNanos
            ));
            maybeScheduleCompaction();
        } catch (Exception exception) {
            backgroundFailure = new IllegalStateException(
                    "Asynchronous flush failed for collection " + definition.name() + " at " + Instant.now(),
                    exception
            );
        } finally {
            flushInProgress.set(false);
            if (backgroundFailure == null) {
                maybeScheduleFlush();
            }
        }
    }

    private void maybeScheduleCompaction() {
        if (backgroundFailure != null || closed.get() || segments.size() < AUTO_COMPACTION_SEGMENT_THRESHOLD) {
            return;
        }
        if (!compactionInProgress.compareAndSet(false, true)) {
            return;
        }

        flushExecutor.submit(() -> {
            try {
                compactSegments();
            } catch (Exception exception) {
                backgroundFailure = new IllegalStateException(
                        "Asynchronous compaction failed for collection " + definition.name() + " at " + Instant.now(),
                        exception
                );
            } finally {
                compactionInProgress.set(false);
                if (backgroundFailure == null) {
                    maybeScheduleCompaction();
                }
            }
        });
    }

    private void validateDimensions(List<UpsertVector> vectors) {
        for (UpsertVector vector : vectors) {
            if (vector.vector().length != definition.dimension()) {
                throw new ValidationException(
                        "Expected vector dimension %d but got %d"
                                .formatted(definition.dimension(), vector.vector().length)
                );
            }
        }
    }

    private void compactSegments() throws IOException {
        long startedAtNanos = System.nanoTime();
        List<ImmutableSegment> candidates;
        Map<String, EntryState> latestSnapshot;
        stateLock.readLock().lock();
        try {
            if (segments.size() < 2) {
                return;
            }
            candidates = segments.stream()
                    .sorted(SEGMENT_ORDER)
                    .toList();
            latestSnapshot = new HashMap<>(latestStates);
        } finally {
            stateLock.readLock().unlock();
        }

        long inputBytes = candidates.stream()
                .mapToLong(candidate -> fileSize(candidate.path()) + fileSize(candidate.searchArtifactPath()))
                .sum();
        List<BufferedSegmentEntry> compactedEntries = buildCompactedEntries(candidates, latestSnapshot);
        ImmutableSegment compactedSegment = compactedEntries.isEmpty()
                ? null
                : SegmentWriter.write(paths, definition, generationCounter.incrementAndGet(), compactedEntries);
        if (compactedSegment != null) {
            searcher.warm(compactedSegment);
        }

        stateLock.writeLock().lock();
        try {
            if (compactedSegment != null) {
                segments.add(compactedSegment);
            }
            segments.removeAll(candidates);
            cleanupCompactedTombstones(candidates);
        } finally {
            stateLock.writeLock().unlock();
        }

        awaitActiveSearches();
        RuntimeException cleanupFailure = null;
        for (ImmutableSegment candidate : candidates) {
            cleanupFailure = deleteCompactedSegment(candidate, cleanupFailure);
        }
        if (cleanupFailure != null) {
            throw cleanupFailure;
        }
        queryCache.clear();
        observer.onCompactionCompleted(new CompactionMetrics(
                definition.tenantId(),
                definition.name(),
                candidates.size(),
                compactedSegment == null ? 0 : 1,
                inputBytes,
                compactedSegment == null ? 0L : fileSize(compactedSegment.path()) + fileSize(compactedSegment.searchArtifactPath()),
                System.nanoTime() - startedAtNanos
        ));
    }

    private List<BufferedSegmentEntry> buildCompactedEntries(List<ImmutableSegment> candidates, Map<String, EntryState> latestSnapshot) {
        ArrayList<BufferedSegmentEntry> entries = new ArrayList<>();
        for (ImmutableSegment candidate : candidates) {
            for (SegmentEntry entry : candidate.entries()) {
                EntryState state = latestSnapshot.get(entry.id());
                if (state == null || state.tombstone() || state.sequence() != entry.sequence()) {
                    continue;
                }
                entries.add(new BufferedSegmentEntry(
                        entry.id(),
                        entry.sequence(),
                        false,
                        entry.norm(),
                        candidate.vectorBytes(entry),
                        entry.payload()
                ));
            }
        }
        entries.sort(Comparator.comparingLong(BufferedSegmentEntry::sequence));
        return List.copyOf(entries);
    }

    private void cleanupCompactedTombstones(List<ImmutableSegment> candidates) {
        for (ImmutableSegment candidate : candidates) {
            for (SegmentEntry entry : candidate.entries()) {
                if (!entry.tombstone()) {
                    continue;
                }
                latestStates.computeIfPresent(entry.id(), (id, state) ->
                        state.tombstone() && state.sequence() == entry.sequence() ? null : state
                );
            }
        }
    }

    private RuntimeException deleteCompactedSegment(ImmutableSegment candidate, RuntimeException failure) {
        searcher.evict(candidate.sourceId());
        RuntimeException updatedFailure = closeQuietly(candidate, failure);
        try {
            Files.deleteIfExists(candidate.path());
            Files.deleteIfExists(candidate.searchArtifactPath());
            return updatedFailure;
        } catch (IOException exception) {
            if (updatedFailure == null) {
                return new UncheckedIOException("Failed to delete compacted segment " + candidate.path(), exception);
            }
            updatedFailure.addSuppressed(exception);
            return updatedFailure;
        }
    }

    private boolean isLiveEntry(String id, Long sequence) {
        EntryState state = latestStates.get(id);
        return state != null && state.sequence() == sequence;
    }

    private void ensureHealthy() {
        if (closed.get()) {
            throw new IllegalStateException("Collection " + definition.name() + " is already closed");
        }
        if (backgroundFailure != null) {
            throw backgroundFailure;
        }
    }

    private void beginSearch() {
        activeSearches.incrementAndGet();
    }

    private void endSearch() {
        if (activeSearches.decrementAndGet() == 0) {
            synchronized (searchLifecycleMonitor) {
                searchLifecycleMonitor.notifyAll();
            }
        }
    }

    private void rewriteActiveWalSnapshot() throws IOException {
        Path temporaryWal = paths.activeWal().resolveSibling("active-recovered.wal");
        Files.deleteIfExists(temporaryWal);

        try (WalAppender recoveredWal = WalAppender.open(temporaryWal, definition.dimension())) {
            List<WalRecord> snapshot = activeMemTable.snapshotEntries().stream()
                    .map(MemTableEntry::toWalRecord)
                    .toList();
            recoveredWal.appendAll(snapshot);
        }

        moveIntoPlace(temporaryWal, paths.activeWal());
    }

    private void awaitActiveSearches() {
        while (activeSearches.get() > 0) {
            try {
                synchronized (searchLifecycleMonitor) {
                    if (activeSearches.get() == 0) {
                        return;
                    }
                    searchLifecycleMonitor.wait(10L);
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while waiting for searches on collection " + definition.name(), exception);
            }
        }
    }

    private void awaitPendingFlushes() {
        while (flushInProgress.get() || !pendingFlushMemTables.isEmpty()) {
            ensureHealthy();
            try {
                Thread.sleep(10L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while flushing collection " + definition.name(), exception);
            }
        }
        ensureHealthy();
    }

    private static EntryState newerState(EntryState left, EntryState right) {
        return left.sequence() >= right.sequence() ? left : right;
    }

    private RuntimeException closeQuietly(AutoCloseable closeable, RuntimeException failure) {
        if (closeable == null) {
            return failure;
        }
        if (closeable instanceof SearchableVectors searchableVectors) {
            searcher.evict(searchableVectors.sourceId());
        }
        try {
            closeable.close();
            return failure;
        } catch (Exception exception) {
            if (failure == null) {
                return new RuntimeException("Failed to close collection " + definition.name(), exception);
            }
            failure.addSuppressed(exception);
            return failure;
        }
    }

    private void moveIntoPlace(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private long estimateStorageBytes() {
        long bytes = activeMemTable == null ? 0L : activeMemTable.approximateBytes();
        for (OffHeapMemTable memTable : pendingFlushMemTables) {
            bytes += memTable.approximateBytes();
        }
        for (ImmutableSegment segment : segments) {
            bytes += fileSize(segment.path());
            bytes += fileSize(segment.searchArtifactPath());
        }
        try {
            for (Path walFile : paths.listWalFiles()) {
                bytes += fileSize(walFile);
            }
        } catch (IOException exception) {
            throw new UncheckedIOException("Failed to compute storage bytes for collection " + definition.name(), exception);
        }
        return bytes;
    }

    private static long fileSize(Path path) {
        if (path == null || !Files.exists(path)) {
            return 0L;
        }
        try {
            return Files.size(path);
        } catch (IOException exception) {
            throw new UncheckedIOException("Failed to read file size for " + path, exception);
        }
    }

    private static void copyFile(Path source, Path target) throws IOException {
        if (target.getParent() != null) {
            Files.createDirectories(target.getParent());
        }
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static void collectionQuarantine(CollectionPaths paths, Path source, String suffix) throws IOException {
        Files.createDirectories(paths.quarantineDirectory());
        Path target = paths.quarantineDirectory().resolve(source.getFileName() + "." + suffix);
        int counter = 0;
        while (Files.exists(target)) {
            counter++;
            target = paths.quarantineDirectory().resolve(source.getFileName() + "." + suffix + "." + counter);
        }
        Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
    }

    private static int estimateFootprint(String id, Map<String, Object> payload, int dimension) {
        int payloadBytes = JsonSupport.estimateBytes(payload);
        return id.getBytes(StandardCharsets.UTF_8).length + payloadBytes + (dimension * Float.BYTES) + Integer.BYTES;
    }

    private record EntryState(long sequence, boolean tombstone) {
        private static EntryState of(long sequence, boolean tombstone) {
            return new EntryState(sequence, tombstone);
        }

        private static EntryState live(long sequence) {
            return new EntryState(sequence, false);
        }

        private static EntryState tombstone(long sequence) {
            return new EntryState(sequence, true);
        }
    }

    private static final class QueryCache {
        private final Map<QueryCacheKey, SearchResponse> entries;

        private QueryCache(int maxEntries) {
            this.entries = java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<>(16, 0.75F, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<QueryCacheKey, SearchResponse> eldest) {
                    return size() > maxEntries;
                }
            });
        }

        private SearchResponse get(SearchRequest request) {
            return entries.get(QueryCacheKey.of(request));
        }

        private void put(SearchRequest request, SearchResponse response) {
            entries.put(QueryCacheKey.of(request), response);
        }

        private void clear() {
            entries.clear();
        }
    }

    private record QueryCacheKey(String vectorSignature, int topK, String filterJson) {
        private static QueryCacheKey of(SearchRequest request) {
            return new QueryCacheKey(
                    Arrays.toString(request.vector()),
                    request.topK(),
                    JsonSupport.writeString(request.filter())
            );
        }
    }
}
