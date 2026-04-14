package cn.suhoan.anaxa.engine;

import cn.suhoan.anaxa.common.error.ValidationException;
import cn.suhoan.anaxa.common.json.JsonSupport;
import cn.suhoan.anaxa.common.model.CollectionDefinition;
import cn.suhoan.anaxa.common.model.CollectionStats;
import cn.suhoan.anaxa.common.model.SearchHit;
import cn.suhoan.anaxa.common.model.SearchRequest;
import cn.suhoan.anaxa.common.model.SearchResponse;
import cn.suhoan.anaxa.common.model.UpsertVector;
import cn.suhoan.anaxa.index.SearchableVectors;
import cn.suhoan.anaxa.index.SegmentIndexSearcher;
import cn.suhoan.anaxa.index.TopKAccumulator;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;

final class EngineCollection implements AutoCloseable {
    private final CollectionDefinition definition;
    private final CollectionPaths paths;
    private final SegmentIndexSearcher searcher;
    private final AtomicLong sequenceGenerator;
    private final AtomicLong generationCounter;
    private final java.util.concurrent.ConcurrentHashMap<String, Long> latestSequences;
    private final CopyOnWriteArrayList<ImmutableSegment> segments;
    private final CopyOnWriteArrayList<OffHeapMemTable> pendingFlushMemTables;
    private final ReentrantReadWriteLock stateLock;
    private final AtomicBoolean flushInProgress;
    private final AtomicBoolean closed;
    private final ExecutorService flushExecutor;

    private volatile OffHeapMemTable activeMemTable;
    private volatile WalAppender activeWal;
    private volatile RuntimeException backgroundFailure;

    private EngineCollection(
            CollectionDefinition definition,
            CollectionPaths paths,
            SegmentIndexSearcher searcher,
            AtomicLong sequenceGenerator,
            AtomicLong generationCounter,
            java.util.concurrent.ConcurrentHashMap<String, Long> latestSequences,
            CopyOnWriteArrayList<ImmutableSegment> segments
    ) {
        this.definition = definition;
        this.paths = paths;
        this.searcher = searcher;
        this.sequenceGenerator = sequenceGenerator;
        this.generationCounter = generationCounter;
        this.latestSequences = latestSequences;
        this.segments = segments;
        this.pendingFlushMemTables = new CopyOnWriteArrayList<>();
        this.stateLock = new ReentrantReadWriteLock();
        this.flushInProgress = new AtomicBoolean(false);
        this.closed = new AtomicBoolean(false);
        this.flushExecutor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("anaxa-flush-", 0).factory());
    }

    static EngineCollection createNew(CollectionDefinition definition, CollectionPaths paths, SegmentIndexSearcher searcher)
            throws IOException {
        Files.createDirectories(paths.root());
        paths.ensureDirectories();
        Files.write(paths.metadataFile(), JsonSupport.writeBytes(definition));

        EngineCollection collection = new EngineCollection(
                definition,
                paths,
                searcher,
                new AtomicLong(0L),
                new AtomicLong(1L),
                new java.util.concurrent.ConcurrentHashMap<>(),
                new CopyOnWriteArrayList<>()
        );
        collection.activeMemTable = new OffHeapMemTable(definition, 1L);
        collection.activeWal = WalAppender.open(paths.activeWal(), definition.dimension());
        return collection;
    }

    static EngineCollection openExisting(CollectionDefinition definition, CollectionPaths paths, SegmentIndexSearcher searcher)
            throws IOException {
        paths.ensureDirectories();

        CopyOnWriteArrayList<ImmutableSegment> loadedSegments = new CopyOnWriteArrayList<>();
        java.util.concurrent.ConcurrentHashMap<String, Long> latestSequences = new java.util.concurrent.ConcurrentHashMap<>();
        long maxSequence = 0L;
        long maxGeneration = 0L;

        for (Path segmentFile : paths.listSegmentFiles()) {
            ImmutableSegment segment = ImmutableSegment.load(segmentFile, definition);
            loadedSegments.add(segment);
            maxGeneration = Math.max(maxGeneration, segment.generation());
            for (SegmentEntry entry : segment.entries()) {
                latestSequences.merge(entry.id(), entry.sequence(), Math::max);
                maxSequence = Math.max(maxSequence, entry.sequence());
            }
        }

        ArrayList<WalRecord> recoveredRecords = new ArrayList<>();
        ArrayList<Path> replayedWalFiles = new ArrayList<>();
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

            recoveredRecords.addAll(WalReplay.readAll(walFile, definition));
            replayedWalFiles.add(walFile);
        }

        recoveredRecords.sort(Comparator.comparingLong(WalRecord::sequence));
        long activeGeneration = Math.max(1L, maxGeneration + 1L);
        EngineCollection collection = new EngineCollection(
                definition,
                paths,
                searcher,
                new AtomicLong(maxSequence),
                new AtomicLong(activeGeneration),
                latestSequences,
                loadedSegments
        );
        collection.activeMemTable = new OffHeapMemTable(definition, activeGeneration);

        for (WalRecord record : recoveredRecords) {
            collection.activeMemTable.upsert(record.id(), record.vector(), record.payload(), record.sequence());
            collection.latestSequences.merge(record.id(), record.sequence(), Math::max);
            collection.sequenceGenerator.updateAndGet(current -> Math.max(current, record.sequence()));
        }

        boolean needsRewrite = replayedWalFiles.stream()
                .map(path -> path.getFileName().toString())
                .anyMatch(fileName -> !"active.wal".equals(fileName));

        if (needsRewrite) {
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
        return new CollectionStats(
                definition.name(),
                definition.dimension(),
                definition.metric(),
                latestSequences.size(),
                segments.size(),
                flushInProgress.get() || !pendingFlushMemTables.isEmpty()
        );
    }

    void upsert(List<UpsertVector> vectors) {
        ensureHealthy();
        stateLock.writeLock().lock();
        try {
            validateDimensions(vectors);
            List<WalRecord> walRecords = new ArrayList<>(vectors.size());
            for (UpsertVector vector : vectors) {
                long sequence = sequenceGenerator.incrementAndGet();
                walRecords.add(new WalRecord(vector.id(), vector.vector(), vector.payload(), sequence));
            }

            activeWal.appendAll(walRecords);
            for (WalRecord record : walRecords) {
                activeMemTable.upsert(record.id(), record.vector(), record.payload(), record.sequence());
                latestSequences.put(record.id(), record.sequence());
            }
        } catch (IOException exception) {
            throw new UncheckedIOException("Failed to append WAL for collection " + definition.name(), exception);
        } finally {
            stateLock.writeLock().unlock();
        }

        maybeScheduleFlush();
    }

    SearchResponse search(SearchRequest request) {
        ensureHealthy();
        if (request.vector().length != definition.dimension()) {
            throw new ValidationException(
                    "Expected search dimension %d but got %d".formatted(definition.dimension(), request.vector().length)
            );
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

        try (StructuredTaskScope<List<SearchHit>, List<List<SearchHit>>> scope = StructuredTaskScope.open(
                StructuredTaskScope.Joiner.<List<SearchHit>>allSuccessfulOrThrow())) {
            for (SearchableVectors source : sources) {
                if (source.size() > 0) {
                    scope.fork(() -> searcher.search(source, request, this::isLiveEntry));
                }
            }

            List<List<SearchHit>> partialResults = scope.join();
            TopKAccumulator accumulator = new TopKAccumulator(request.topK());
            for (List<SearchHit> partialResult : partialResults) {
                partialResult.forEach(accumulator::offer);
            }
            return new SearchResponse(accumulator.toSortedList());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Search interrupted for collection " + definition.name(), exception);
        } catch (RuntimeException exception) {
            throw exception;
        } catch (Throwable throwable) {
            throw new RuntimeException("Search failed for collection " + definition.name(), throwable);
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }

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
        if (backgroundFailure != null || activeMemTable.approximateBytes() < definition.flushThresholdBytes()) {
            return;
        }
        if (!flushInProgress.compareAndSet(false, true)) {
            return;
        }

        OffHeapMemTable frozenMemTable;
        Path frozenWalPath;
        stateLock.writeLock().lock();
        try {
            if (backgroundFailure != null || activeMemTable.approximateBytes() < definition.flushThresholdBytes()) {
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
        try {
            ImmutableSegment segment = SegmentWriter.write(paths, definition, frozenMemTable);
            if (segment != null) {
                segments.add(segment);
            }
            pendingFlushMemTables.remove(frozenMemTable);
            Files.deleteIfExists(frozenWalPath);
            frozenMemTable.close();
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

    private boolean isLiveEntry(String id, Long sequence) {
        return Objects.equals(latestSequences.get(id), sequence);
    }

    private void ensureHealthy() {
        if (closed.get()) {
            throw new IllegalStateException("Collection " + definition.name() + " is already closed");
        }
        if (backgroundFailure != null) {
            throw backgroundFailure;
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

    private RuntimeException closeQuietly(AutoCloseable closeable, RuntimeException failure) {
        if (closeable == null) {
            return failure;
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
}
