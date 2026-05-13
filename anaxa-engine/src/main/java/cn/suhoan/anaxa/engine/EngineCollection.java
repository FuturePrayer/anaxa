package cn.suhoan.anaxa.engine;

import cn.suhoan.anaxa.common.error.ValidationException;
import cn.suhoan.anaxa.common.json.JsonSupport;
import cn.suhoan.anaxa.common.model.CollectionDefinition;
import cn.suhoan.anaxa.common.model.CollectionStats;
import cn.suhoan.anaxa.common.model.PartialUpdateVector;
import cn.suhoan.anaxa.common.model.SearchHit;
import cn.suhoan.anaxa.common.model.SearchRequest;
import cn.suhoan.anaxa.common.model.SearchResponse;
import cn.suhoan.anaxa.common.model.UpsertVector;
import cn.suhoan.anaxa.common.util.PayloadPatches;
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
import java.nio.file.NoSuchFileException;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;

final class EngineCollection implements AutoCloseable {
    private static final int AUTO_COMPACTION_SEGMENT_THRESHOLD = 4;
    private static final long STARTUP_WARM_BUDGET_BYTES = 256L * 1024L * 1024L;
    private static final long RESIDENT_CACHE_BUDGET_BYTES = 512L * 1024L * 1024L;
    private static final long MIN_ADAPTIVE_FLUSH_THRESHOLD_BYTES = 512L * 1024L;
    private static final int MIN_ADAPTIVE_FLUSH_MUTATIONS = 256;
    private static final int MAX_ADAPTIVE_FLUSH_MUTATIONS = 4_096;
    private static final int MAX_WARM_QUEUE_DEPTH = 8;
    private static final long STALE_VERSION_COMPACTION_THRESHOLD = 512L;
    private static final long TOMBSTONE_COMPACTION_THRESHOLD = 256L;
    private static final double TOMBSTONE_RATIO_COMPACTION_THRESHOLD = 0.15D;
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
    private final AtomicLong liveVectorCount;
    private final AtomicLong tombstoneCount;
    private final AtomicLong staleVersionDebt;
    private final AtomicLong tombstoneDebt;
    private final ExecutorService flushExecutor;
    private final ExecutorService prefetchExecutor;
    private final QueryCache queryCache;
    private final Object searchLifecycleMonitor;
    private final ConcurrentHashMap<String, ResidentSource> residentSources;
    private final AtomicLong residentSourceBytes;
    private final AtomicInteger queuedWarmTasks;

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
        long initialLiveVectorCount = latestStates.values().stream().filter(state -> !state.tombstone()).count();
        long initialTombstoneCount = latestStates.size() - initialLiveVectorCount;
        this.liveVectorCount = new AtomicLong(initialLiveVectorCount);
        this.tombstoneCount = new AtomicLong(initialTombstoneCount);
        this.staleVersionDebt = new AtomicLong(Math.max(0L, persistedEntryCount(segments) - latestStates.size()));
        this.tombstoneDebt = new AtomicLong(persistedTombstoneCount(segments));
        this.flushExecutor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("anaxa-flush-", 0).factory());
        this.prefetchExecutor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("anaxa-warm-", 0).factory());
        this.queryCache = new QueryCache(128);
        this.searchLifecycleMonitor = new Object();
        this.residentSources = new ConcurrentHashMap<>();
        this.residentSourceBytes = new AtomicLong();
        this.queuedWarmTasks = new AtomicInteger();
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
                collection.replaceLatestState(record.id(), EntryState.tombstone(record.sequence()));
            } else if (record.payloadPatch()) {
                collection.applyRecoveredPayloadPatch(record);
            } else {
                collection.activeMemTable.upsert(record.id(), record.vector(), record.payload(), record.sequence());
                collection.replaceLatestState(record.id(), EntryState.live(record.sequence()));
            }
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
        collection.scheduleResidentWarmup();
        return collection;
    }

    CollectionStats stats() {
        return new CollectionStats(
                definition.name(),
                definition.dimension(),
                definition.metric(),
                liveVectorCount.get(),
                tombstoneCount.get(),
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

    long estimatePartialUpdateBytes(List<PartialUpdateVector> updates) {
        ensureHealthy();
        stateLock.readLock().lock();
        try {
            long bytes = 0L;
            for (PartialUpdateVector update : normalizePartialUpdates(updates)) {
                ResolvedDocument current = resolveLiveDocument(update.id());
                Map<String, Object> mergedPayload = PayloadPatches.merge(current.payload(), update.payload());
                bytes += estimateFootprint(update.id(), mergedPayload, definition.dimension());
            }
            return bytes;
        } finally {
            stateLock.readLock().unlock();
        }
    }

    void upsert(List<UpsertVector> vectors) {
        ensureHealthy();
        stateLock.writeLock().lock();
        try {
            validateDimensions(vectors);
            List<WalRecord> walRecords = new ArrayList<>(vectors.size());
            for (UpsertVector vector : vectors) {
                long sequence = sequenceGenerator.incrementAndGet();
                walRecords.add(WalRecord.liveTrusted(vector.id(), vector.vector(), vector.payload(), sequence));
            }

            activeWal.appendAll(walRecords);
            for (WalRecord record : walRecords) {
                if (latestStates.containsKey(record.id())) {
                    staleVersionDebt.incrementAndGet();
                }
                activeMemTable.upsert(record.id(), record.vector(), record.payload(), record.sequence());
                replaceLatestState(record.id(), EntryState.live(record.sequence()));
            }
        } catch (IOException exception) {
            throw new UncheckedIOException("Failed to append WAL for collection " + definition.name(), exception);
        } finally {
            stateLock.writeLock().unlock();
        }

        queryCache.clear();
        handleMutationFlushPressure();
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
                staleVersionDebt.incrementAndGet();
                tombstoneDebt.incrementAndGet();
                activeMemTable.tombstone(record.id(), record.sequence());
                replaceLatestState(record.id(), EntryState.tombstone(record.sequence()));
            }
        } catch (IOException exception) {
            throw new UncheckedIOException("Failed to append delete tombstones for collection " + definition.name(), exception);
        } finally {
            stateLock.writeLock().unlock();
        }

        queryCache.clear();
        handleMutationFlushPressure();
    }

    void partialUpdate(List<PartialUpdateVector> updates) {
        ensureHealthy();
        stateLock.writeLock().lock();
        try {
            List<PartialUpdateVector> normalizedUpdates = normalizePartialUpdates(updates);
            ArrayList<ResolvedDocument> resolvedDocuments = new ArrayList<>(normalizedUpdates.size());
            ArrayList<WalRecord> walRecords = new ArrayList<>(normalizedUpdates.size());
            for (PartialUpdateVector update : normalizedUpdates) {
                ResolvedDocument current = resolveLiveDocument(update.id());
                Map<String, Object> mergedPayload = PayloadPatches.merge(current.payload(), update.payload());
                long sequence = sequenceGenerator.incrementAndGet();
                walRecords.add(WalRecord.payloadPatchTrusted(update.id(), update.payload(), sequence));
                resolvedDocuments.add(new ResolvedDocument(update.id(), sequence, current.vector(), mergedPayload));
            }

            activeWal.appendAll(walRecords);
            for (ResolvedDocument resolved : resolvedDocuments) {
                staleVersionDebt.incrementAndGet();
                activeMemTable.upsert(resolved.id(), resolved.vector(), resolved.payload(), resolved.sequence());
                replaceLatestState(resolved.id(), EntryState.live(resolved.sequence()));
            }
        } catch (IOException exception) {
            throw new UncheckedIOException("Failed to append payload updates for collection " + definition.name(), exception);
        } finally {
            stateLock.writeLock().unlock();
        }

        queryCache.clear();
        handleMutationFlushPressure();
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

            List<SearchableVectors> activeSources = sources.stream()
                    .filter(source -> source.size() > 0)
                    .toList();
            activeSources.forEach(source -> touchResidentSource(source.sourceId()));

            try {
                ArrayList<SourceSearchResult> partialResults = new ArrayList<>(activeSources.size());
                if (activeSources.size() == 1) {
                    partialResults.add(searcher.search(activeSources.getFirst(), request, this::isLiveEntry));
                } else if (activeSources.size() > 1) {
                    SearchableVectors inlineSource = selectInlineSource(activeSources);
                    try (StructuredTaskScope<SourceSearchResult, List<SourceSearchResult>> scope = StructuredTaskScope.open(
                            StructuredTaskScope.Joiner.<SourceSearchResult>allSuccessfulOrThrow())) {
                        for (SearchableVectors source : activeSources) {
                            if (source != inlineSource) {
                                scope.fork(() -> searcher.search(source, request, this::isLiveEntry));
                            }
                        }
                        SourceSearchResult inlineResult = searcher.search(inlineSource, request, this::isLiveEntry);
                        partialResults.addAll(scope.join());
                        partialResults.add(inlineResult);
                    }
                }

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

        prefetchExecutor.shutdown();
        try {
            if (!prefetchExecutor.awaitTermination(10L, TimeUnit.SECONDS)) {
                prefetchExecutor.shutdownNow();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            prefetchExecutor.shutdownNow();
            if (failure == null) {
                failure = new RuntimeException("Interrupted while closing warmup tasks for collection " + definition.name(), exception);
            } else {
                failure.addSuppressed(exception);
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
        if (activeMemTable.mutationCount() >= effectiveFlushMutationThreshold()) {
            scheduleFlush(true);
            return;
        }
        scheduleFlush(false);
    }

    private void handleMutationFlushPressure() {
        if (activeMemTable.mutationCount() >= effectiveFlushMutationThreshold() && staleVersionDebt.get() >= 64L) {
            scheduleFlush(true);
            awaitPendingFlushes();
            return;
        }
        maybeScheduleFlush();
    }

    private void scheduleFlush(boolean force) {
        if (backgroundFailure != null) {
            return;
        }
        if (!force && !shouldFlushActiveMemTable()) {
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
                    || (!force && !shouldFlushActiveMemTable())
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
                scheduleWarmSource(segment);
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
                maybeScheduleCompaction();
            }
        }
    }

    private void maybeScheduleCompaction() {
        if (backgroundFailure != null || closed.get() || !shouldCompact()) {
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

    private boolean shouldFlushActiveMemTable() {
        return activeMemTable.approximateBytes() >= effectiveFlushThresholdBytes()
                || activeMemTable.mutationCount() >= effectiveFlushMutationThreshold();
    }

    private long effectiveFlushThresholdBytes() {
        long baseThresholdBytes = definition.flushThresholdBytes();
        long threshold = baseThresholdBytes;
        if (activeMemTable.size() > 0) {
            double tombstoneRatio = activeMemTable.tombstoneEntryCount() / (double) activeMemTable.size();
            if (tombstoneRatio >= 0.5D) {
                threshold = Math.max(MIN_ADAPTIVE_FLUSH_THRESHOLD_BYTES, baseThresholdBytes / 8L);
            } else if (tombstoneRatio >= 0.25D || staleVersionDebt.get() >= STALE_VERSION_COMPACTION_THRESHOLD / 2L) {
                threshold = Math.max(MIN_ADAPTIVE_FLUSH_THRESHOLD_BYTES, baseThresholdBytes / 4L);
            } else if (activeMemTable.mutationCount() >= effectiveFlushMutationThreshold() / 2L) {
                threshold = Math.max(MIN_ADAPTIVE_FLUSH_THRESHOLD_BYTES, baseThresholdBytes / 2L);
            }
        }
        return Math.min(baseThresholdBytes, threshold);
    }

    private int effectiveFlushMutationThreshold() {
        int dimension = definition.dimension();
        int baseThreshold;
        if (dimension >= 1_536) {
            baseThreshold = 256;
        } else if (dimension >= 768) {
            baseThreshold = 512;
        } else if (dimension >= 384) {
            baseThreshold = 512;
        } else {
            baseThreshold = 256;
        }
        if (activeMemTable.size() > 0 && activeMemTable.tombstoneEntryCount() * 4L >= activeMemTable.size()) {
            baseThreshold /= 2;
        }
        if (staleVersionDebt.get() >= 64L) {
            baseThreshold = Math.min(baseThreshold, 256);
        }
        if (staleVersionDebt.get() >= STALE_VERSION_COMPACTION_THRESHOLD) {
            baseThreshold /= 2;
        }
        return Math.max(MIN_ADAPTIVE_FLUSH_MUTATIONS, Math.min(MAX_ADAPTIVE_FLUSH_MUTATIONS, baseThreshold));
    }

    private boolean shouldCompact() {
        if (segments.size() < 2 || flushInProgress.get() || !pendingFlushMemTables.isEmpty()) {
            return false;
        }
        if (segments.size() >= AUTO_COMPACTION_SEGMENT_THRESHOLD) {
            return true;
        }
        long liveCount = liveVectorCount.get();
        long tombstoneCountSnapshot = tombstoneCount.get();
        long totalCount = liveCount + tombstoneCountSnapshot;
        double tombstoneRatio = totalCount == 0L ? 0D : tombstoneCountSnapshot / (double) totalCount;
        return tombstoneDebt.get() >= TOMBSTONE_COMPACTION_THRESHOLD
                || staleVersionDebt.get() >= Math.max(STALE_VERSION_COMPACTION_THRESHOLD, liveCount / 2L)
                || tombstoneRatio >= TOMBSTONE_RATIO_COMPACTION_THRESHOLD;
    }

    private void scheduleResidentWarmup() {
        if (segments.isEmpty()) {
            return;
        }
        List<ImmutableSegment> warmupSnapshot = List.copyOf(segments);
        long warmedBytes = 0L;
        for (int index = warmupSnapshot.size() - 1; index >= 0; index--) {
            ImmutableSegment segment = warmupSnapshot.get(index);
            long segmentBytes = Math.max(1L, segment.approximateBytes());
            if (warmedBytes > 0L && warmedBytes + segmentBytes > STARTUP_WARM_BUDGET_BYTES) {
                break;
            }
            scheduleWarmSource(segment);
            warmedBytes += segmentBytes;
        }
    }

    private void warmSourceBestEffort(SearchableVectors source) {
        try {
            searcher.warm(source);
            rememberResidentSource(source);
        } catch (RuntimeException exception) {
            searcher.evict(source.sourceId());
            forgetResidentSource(source.sourceId());
        }
    }

    private void refreshCompactionPressure() {
        staleVersionDebt.set(Math.max(0L, persistedEntryCount(segments) - latestStates.size()));
        tombstoneDebt.set(persistedTombstoneCount(segments));
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

    private void applyRecoveredPayloadPatch(WalRecord record) {
        ResolvedDocument current = resolveLiveDocument(record.id());
        Map<String, Object> mergedPayload = PayloadPatches.merge(current.payload(), record.payload());
        activeMemTable.upsert(record.id(), current.vector(), mergedPayload, record.sequence());
        replaceLatestState(record.id(), EntryState.live(record.sequence()));
    }

    private List<PartialUpdateVector> normalizePartialUpdates(List<PartialUpdateVector> updates) {
        LinkedHashSet<String> orderedIds = new LinkedHashSet<>();
        HashMap<String, Map<String, Object>> mergedPayloads = new HashMap<>();
        for (PartialUpdateVector update : updates) {
            orderedIds.add(update.id());
            mergedPayloads.merge(update.id(), update.payload(), PayloadPatches::merge);
        }
        return orderedIds.stream()
                .map(id -> new PartialUpdateVector(id, mergedPayloads.get(id)))
                .toList();
    }

    private ResolvedDocument resolveLiveDocument(String id) {
        EntryState state = latestStates.get(id);
        if (state == null || state.tombstone()) {
            throw new ValidationException("Vector " + id + " does not exist or has been deleted");
        }

        MemTableEntry activeEntry = activeMemTable.entry(id);
        if (activeEntry != null && activeEntry.sequence() == state.sequence() && !activeEntry.tombstone()) {
            return new ResolvedDocument(id, state.sequence(), activeEntry.vector(), activeEntry.payload());
        }

        for (int index = pendingFlushMemTables.size() - 1; index >= 0; index--) {
            MemTableEntry entry = pendingFlushMemTables.get(index).entry(id);
            if (entry != null && entry.sequence() == state.sequence() && !entry.tombstone()) {
                return new ResolvedDocument(id, state.sequence(), entry.vector(), entry.payload());
            }
        }

        for (int index = segments.size() - 1; index >= 0; index--) {
            ImmutableSegment segment = segments.get(index);
            SegmentEntry entry = segment.entry(id);
            if (entry != null && entry.sequence() == state.sequence() && !entry.tombstone()) {
                return new ResolvedDocument(id, state.sequence(), segment.vector(entry), entry.payload());
            }
        }

        throw new IllegalStateException(
                "Failed to resolve live vector state for " + id + " in collection " + definition.name()
        );
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
            scheduleWarmSource(compactedSegment);
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

        refreshCompactionPressure();
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
                removeLatestTombstoneState(entry.id(), entry.sequence());
            }
        }
    }

    private RuntimeException deleteCompactedSegment(ImmutableSegment candidate, RuntimeException failure) {
        forgetResidentSource(candidate.sourceId());
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

    private SearchableVectors selectInlineSource(List<SearchableVectors> sources) {
        SearchableVectors inlineSource = null;
        int largestSize = -1;
        for (SearchableVectors source : sources) {
            if (source.size() > largestSize) {
                largestSize = source.size();
                inlineSource = source;
            }
        }
        return Objects.requireNonNull(inlineSource, "inlineSource");
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

    private void replaceLatestState(String id, EntryState next) {
        EntryState previous = latestStates.put(id, next);
        adjustStateCounts(previous, next);
    }

    private void removeLatestTombstoneState(String id, long sequence) {
        latestStates.computeIfPresent(id, (ignored, state) -> {
            if (state.tombstone() && state.sequence() == sequence) {
                adjustStateCounts(state, null);
                return null;
            }
            return state;
        });
    }

    private void adjustStateCounts(EntryState previous, EntryState next) {
        if (previous != null) {
            if (previous.tombstone()) {
                tombstoneCount.decrementAndGet();
            } else {
                liveVectorCount.decrementAndGet();
            }
        }
        if (next != null) {
            if (next.tombstone()) {
                tombstoneCount.incrementAndGet();
            } else {
                liveVectorCount.incrementAndGet();
            }
        }
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

    private void scheduleWarmSource(SearchableVectors source) {
        if (!(source instanceof ImmutableSegment) || source.approximateBytes() <= 0L || backgroundFailure != null || closed.get()) {
            return;
        }
        touchResidentSource(source.sourceId());
        if (residentSources.containsKey(source.sourceId())) {
            return;
        }
        if (queuedWarmTasks.incrementAndGet() > MAX_WARM_QUEUE_DEPTH) {
            queuedWarmTasks.decrementAndGet();
            return;
        }
        try {
            prefetchExecutor.submit(() -> {
                beginSearch();
                try {
                    warmSourceBestEffort(source);
                } finally {
                    endSearch();
                    queuedWarmTasks.decrementAndGet();
                }
            });
        } catch (RejectedExecutionException exception) {
            queuedWarmTasks.decrementAndGet();
            if (!closed.get()) {
                throw exception;
            }
        }
    }

    private void rememberResidentSource(SearchableVectors source) {
        ResidentSource updated = new ResidentSource(source.approximateBytes(), System.nanoTime());
        ResidentSource previous = residentSources.put(source.sourceId(), updated);
        residentSourceBytes.addAndGet(updated.approximateBytes() - (previous == null ? 0L : previous.approximateBytes()));
        trimResidentSources(source.sourceId());
    }

    private void touchResidentSource(String sourceId) {
        ResidentSource resident = residentSources.get(sourceId);
        if (resident != null) {
            resident.touch(System.nanoTime());
        }
    }

    private void forgetResidentSource(String sourceId) {
        ResidentSource removed = residentSources.remove(sourceId);
        if (removed != null) {
            residentSourceBytes.addAndGet(-removed.approximateBytes());
        }
    }

    private void trimResidentSources(String protectedSourceId) {
        if (residentSourceBytes.get() <= RESIDENT_CACHE_BUDGET_BYTES) {
            return;
        }
        List<Map.Entry<String, ResidentSource>> candidates = residentSources.entrySet().stream()
                .filter(entry -> !entry.getKey().equals(protectedSourceId))
                .sorted(Map.Entry.comparingByValue(Comparator.comparingLong(ResidentSource::lastAccessNanos)))
                .toList();
        for (Map.Entry<String, ResidentSource> candidate : candidates) {
            if (residentSourceBytes.get() <= RESIDENT_CACHE_BUDGET_BYTES) {
                return;
            }
            if (residentSources.remove(candidate.getKey(), candidate.getValue())) {
                residentSourceBytes.addAndGet(-candidate.getValue().approximateBytes());
                searcher.evict(candidate.getKey());
            }
        }
    }

    private static final class ResidentSource {
        private final long approximateBytes;
        private volatile long lastAccessNanos;

        private ResidentSource(long approximateBytes, long lastAccessNanos) {
            this.approximateBytes = approximateBytes;
            this.lastAccessNanos = lastAccessNanos;
        }

        private long approximateBytes() {
            return approximateBytes;
        }

        private long lastAccessNanos() {
            return lastAccessNanos;
        }

        private void touch(long nowNanos) {
            lastAccessNanos = nowNanos;
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
        } catch (NoSuchFileException exception) {
            return 0L;
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

    private static long persistedEntryCount(List<ImmutableSegment> segments) {
        long count = 0L;
        for (ImmutableSegment segment : segments) {
            count += segment.entries().size();
        }
        return count;
    }

    private static long persistedTombstoneCount(List<ImmutableSegment> segments) {
        long count = 0L;
        for (ImmutableSegment segment : segments) {
            for (SegmentEntry entry : segment.entries()) {
                if (entry.tombstone()) {
                    count++;
                }
            }
        }
        return count;
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

    private record ResolvedDocument(String id, long sequence, float[] vector, Map<String, Object> payload) {
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
