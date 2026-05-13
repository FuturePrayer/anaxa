package cn.suhoan.anaxa.engine;

import cn.suhoan.anaxa.common.error.NotFoundException;
import cn.suhoan.anaxa.common.error.ValidationException;
import cn.suhoan.anaxa.common.json.JsonSupport;
import cn.suhoan.anaxa.common.model.BackupIds;
import cn.suhoan.anaxa.common.model.CollectionDefinition;
import cn.suhoan.anaxa.common.model.CollectionStats;
import cn.suhoan.anaxa.common.model.CreateCollectionRequest;
import cn.suhoan.anaxa.common.model.DeleteVectorsRequest;
import cn.suhoan.anaxa.common.model.PartialUpdateVector;
import cn.suhoan.anaxa.common.model.PartialUpdateVectorsRequest;
import cn.suhoan.anaxa.common.model.SearchRequest;
import cn.suhoan.anaxa.common.model.SearchResponse;
import cn.suhoan.anaxa.common.model.UpsertVector;
import cn.suhoan.anaxa.common.model.UpsertVectorsRequest;
import cn.suhoan.anaxa.index.HnswPqSegmentIndexSearcher;
import cn.suhoan.anaxa.index.SegmentIndexSearcher;
import cn.suhoan.anaxa.storage.CollectionPaths;
import cn.suhoan.anaxa.storage.ImmutableSegment;
import cn.suhoan.anaxa.storage.SegmentEntry;
import cn.suhoan.anaxa.storage.WalRecord;
import cn.suhoan.anaxa.storage.WalReplay;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

public final class VectorDatabaseEngine implements AutoCloseable {
    public static final long DEFAULT_FLUSH_THRESHOLD_BYTES = 64L * 1024L * 1024L;

    private final Path dataDirectory;
    private final long defaultFlushThresholdBytes;
    private final SegmentIndexSearcher searcher;
    private final EngineObserver observer;
    private final ConcurrentHashMap<CollectionKey, EngineCollection> collections;

    public VectorDatabaseEngine(Path dataDirectory) throws IOException {
        this(dataDirectory, DEFAULT_FLUSH_THRESHOLD_BYTES, new HnswPqSegmentIndexSearcher(), EngineObserver.NO_OP);
    }

    public VectorDatabaseEngine(Path dataDirectory, long defaultFlushThresholdBytes) throws IOException {
        this(dataDirectory, defaultFlushThresholdBytes, new HnswPqSegmentIndexSearcher(), EngineObserver.NO_OP);
    }

    public VectorDatabaseEngine(Path dataDirectory, long defaultFlushThresholdBytes, EngineObserver observer) throws IOException {
        this(dataDirectory, defaultFlushThresholdBytes, new HnswPqSegmentIndexSearcher(), observer);
    }

    VectorDatabaseEngine(
            Path dataDirectory,
            long defaultFlushThresholdBytes,
            SegmentIndexSearcher searcher,
            EngineObserver observer
    ) throws IOException {
        this.dataDirectory = Objects.requireNonNull(dataDirectory, "dataDirectory");
        this.defaultFlushThresholdBytes = defaultFlushThresholdBytes;
        this.searcher = Objects.requireNonNull(searcher, "searcher");
        this.observer = Objects.requireNonNull(observer, "observer");
        this.collections = new ConcurrentHashMap<>();
        Files.createDirectories(dataDirectory);
        loadExistingCollections();
    }

    public CollectionDefinition createCollection(CreateCollectionRequest request) {
        return createCollection(CollectionDefinition.DEFAULT_TENANT, request);
    }

    public CollectionDefinition createCollection(String tenantId, CreateCollectionRequest request) {
        CollectionDefinition definition = request.toDefinition(tenantId, defaultFlushThresholdBytes);
        CollectionPaths paths = CollectionPaths.of(dataDirectory, definition.tenantId(), definition.name());
        CollectionKey key = new CollectionKey(definition.tenantId(), definition.name());
        if (collections.containsKey(key) || Files.exists(paths.metadataFile())) {
            throw new ValidationException("Collection already exists: " + definition.tenantId() + "/" + definition.name());
        }

        EngineCollection collection;
        try {
            collection = EngineCollection.createNew(definition, paths, searcher, observer);
        } catch (IOException exception) {
            throw new UncheckedIOException(
                    "Failed to create collection " + definition.tenantId() + "/" + definition.name(),
                    exception
            );
        }

        EngineCollection existing = collections.putIfAbsent(key, collection);
        if (existing != null) {
            collection.close();
            throw new ValidationException("Collection already exists: " + definition.tenantId() + "/" + definition.name());
        }
        return definition;
    }

    public void upsert(String collectionName, UpsertVectorsRequest request) {
        upsert(CollectionDefinition.DEFAULT_TENANT, collectionName, request);
    }

    public void upsert(String tenantId, String collectionName, UpsertVectorsRequest request) {
        collection(tenantId, collectionName).upsert(request.vectors());
    }

    public long estimateAdditionalLiveVectors(String tenantId, String collectionName, UpsertVectorsRequest request) {
        List<String> ids = request.vectors().stream().map(UpsertVector::id).toList();
        return collection(tenantId, collectionName).estimateAdditionalLiveVectors(ids);
    }

    public long estimateUpsertBytes(String tenantId, String collectionName, UpsertVectorsRequest request) {
        return collection(tenantId, collectionName).estimateUpsertBytes(request.vectors());
    }

    public long estimatePartialUpdateBytes(String tenantId, String collectionName, PartialUpdateVectorsRequest request) {
        return collection(tenantId, collectionName).estimatePartialUpdateBytes(request.updates());
    }

    public void delete(String collectionName, DeleteVectorsRequest request) {
        delete(CollectionDefinition.DEFAULT_TENANT, collectionName, request);
    }

    public void delete(String tenantId, String collectionName, DeleteVectorsRequest request) {
        collection(tenantId, collectionName).delete(request.ids());
    }

    public void partialUpdate(String collectionName, PartialUpdateVectorsRequest request) {
        partialUpdate(CollectionDefinition.DEFAULT_TENANT, collectionName, request);
    }

    public void partialUpdate(String tenantId, String collectionName, PartialUpdateVectorsRequest request) {
        collection(tenantId, collectionName).partialUpdate(request.updates());
    }

    public SearchResponse search(String collectionName, SearchRequest request) {
        return search(CollectionDefinition.DEFAULT_TENANT, collectionName, request);
    }

    public SearchResponse search(String tenantId, String collectionName, SearchRequest request) {
        return collection(tenantId, collectionName).search(request);
    }

    public void compact(String collectionName) {
        compact(CollectionDefinition.DEFAULT_TENANT, collectionName);
    }

    public void compact(String tenantId, String collectionName) {
        collection(tenantId, collectionName).compact();
    }

    public void flush(String collectionName) {
        flush(CollectionDefinition.DEFAULT_TENANT, collectionName);
    }

    public void flush(String tenantId, String collectionName) {
        collection(tenantId, collectionName).flush();
    }

    public CollectionStats backupCollection(String collectionName, String backupId, Path backupDirectory) {
        return backupCollection(CollectionDefinition.DEFAULT_TENANT, collectionName, backupId, backupDirectory);
    }

    public CollectionStats backupCollection(String tenantId, String collectionName, String backupId, Path backupDirectory) {
        String normalizedTenantId = CollectionDefinition.normalizeTenantId(tenantId);
        String normalizedBackupId = BackupIds.normalize(backupId);
        Objects.requireNonNull(backupDirectory, "backupDirectory");
        CollectionPaths backupPaths = CollectionPaths.of(backupDirectory.resolve(normalizedBackupId), normalizedTenantId, collectionName);
        try {
            collection(normalizedTenantId, collectionName).writeBackup(backupPaths);
            return stats(normalizedTenantId, collectionName);
        } catch (IOException exception) {
            throw new UncheckedIOException(
                    "Failed to back up collection " + normalizedTenantId + "/" + collectionName,
                    exception
            );
        }
    }

    public CollectionStats restoreCollection(
            String sourceCollectionName,
            String targetCollectionName,
            String backupId,
            Path backupDirectory
    ) {
        return restoreCollection(
                CollectionDefinition.DEFAULT_TENANT,
                sourceCollectionName,
                targetCollectionName,
                backupId,
                backupDirectory
        );
    }

    public CollectionStats restoreCollection(
            String tenantId,
            String sourceCollectionName,
            String targetCollectionName,
            String backupId,
            Path backupDirectory
    ) {
        String normalizedTenantId = CollectionDefinition.normalizeTenantId(tenantId);
        Objects.requireNonNull(sourceCollectionName, "sourceCollectionName");
        Objects.requireNonNull(targetCollectionName, "targetCollectionName");
        String normalizedBackupId = BackupIds.normalize(backupId);
        Objects.requireNonNull(backupDirectory, "backupDirectory");

        CollectionPaths sourcePaths = CollectionPaths.of(backupDirectory.resolve(normalizedBackupId), normalizedTenantId, sourceCollectionName);
        if (!Files.exists(sourcePaths.metadataFile())) {
            throw new NotFoundException("Backup not found: " + normalizedBackupId + "/" + normalizedTenantId + "/" + sourceCollectionName);
        }

        CollectionPaths targetPaths = CollectionPaths.of(dataDirectory, normalizedTenantId, targetCollectionName);
        CollectionKey key = new CollectionKey(normalizedTenantId, targetCollectionName);
        if (collections.containsKey(key) || Files.exists(targetPaths.root())) {
            throw new ValidationException("Collection already exists: " + normalizedTenantId + "/" + targetCollectionName);
        }

        try {
            CollectionDefinition sourceDefinition = JsonSupport.read(Files.readAllBytes(sourcePaths.metadataFile()), CollectionDefinition.class);
            CollectionDefinition restoredDefinition = new CollectionDefinition(
                    targetCollectionName,
                    sourceDefinition.dimension(),
                    sourceDefinition.metric(),
                    sourceDefinition.flushThresholdBytes(),
                    normalizedTenantId
            );

            copyDirectory(sourcePaths.root(), targetPaths.root());
            Files.write(targetPaths.metadataFile(), JsonSupport.writeBytes(restoredDefinition));

            EngineCollection restored = EngineCollection.openExisting(restoredDefinition, targetPaths, searcher, observer);
            EngineCollection existing = collections.putIfAbsent(key, restored);
            if (existing != null) {
                restored.close();
                deleteDirectory(targetPaths.root());
                throw new ValidationException("Collection already exists: " + normalizedTenantId + "/" + targetCollectionName);
            }
            return restored.stats();
        } catch (IOException exception) {
            try {
                deleteDirectory(targetPaths.root());
            } catch (IOException cleanupException) {
                exception.addSuppressed(cleanupException);
            }
            throw new UncheckedIOException(
                    "Failed to restore collection "
                            + normalizedTenantId
                            + "/"
                            + sourceCollectionName
                            + " from backup "
                            + normalizedBackupId,
                    exception
            );
        }
    }

    public CollectionStats previewBackupCollection(String tenantId, String sourceCollectionName, String backupId, Path backupDirectory) {
        String normalizedTenantId = CollectionDefinition.normalizeTenantId(tenantId);
        Objects.requireNonNull(sourceCollectionName, "sourceCollectionName");
        String normalizedBackupId = BackupIds.normalize(backupId);
        Objects.requireNonNull(backupDirectory, "backupDirectory");

        CollectionPaths sourcePaths = CollectionPaths.of(backupDirectory.resolve(normalizedBackupId), normalizedTenantId, sourceCollectionName);
        if (!Files.exists(sourcePaths.metadataFile())) {
            throw new NotFoundException("Backup not found: " + normalizedBackupId + "/" + normalizedTenantId + "/" + sourceCollectionName);
        }

        try {
            return previewCollectionStats(sourcePaths, normalizedTenantId);
        } catch (IOException exception) {
            throw new UncheckedIOException(
                    "Failed to inspect backup " + normalizedBackupId + "/" + normalizedTenantId + "/" + sourceCollectionName,
                    exception
            );
        }
    }

    public CollectionStats stats(String collectionName) {
        return stats(CollectionDefinition.DEFAULT_TENANT, collectionName);
    }

    public CollectionStats stats(String tenantId, String collectionName) {
        return collection(tenantId, collectionName).stats();
    }

    public List<CollectionStats> listCollections() {
        return collections.values().stream()
                .map(EngineCollection::stats)
                .sorted(Comparator.comparing(CollectionStats::tenantId).thenComparing(CollectionStats::name))
                .toList();
    }

    public List<CollectionStats> listCollections(String tenantId) {
        String normalizedTenantId = CollectionDefinition.normalizeTenantId(tenantId);
        return collections.entrySet().stream()
                .filter(entry -> entry.getKey().tenantId().equals(normalizedTenantId))
                .map(entry -> entry.getValue().stats())
                .sorted(Comparator.comparing(CollectionStats::name))
                .toList();
    }

    @Override
    public void close() {
        RuntimeException failure = null;
        try {
            for (EngineCollection collection : collections.values()) {
                try {
                    collection.close();
                } catch (RuntimeException exception) {
                    if (failure == null) {
                        failure = exception;
                    } else {
                        failure.addSuppressed(exception);
                    }
                }
            }
        } finally {
            searcher.close();
        }

        if (failure != null) {
            throw failure;
        }
    }

    private void loadExistingCollections() throws IOException {
        loadDefaultTenantCollections();
        Path tenantsRoot = dataDirectory.resolve("tenants");
        if (!Files.isDirectory(tenantsRoot)) {
            return;
        }
        try (Stream<Path> tenantDirectories = Files.list(tenantsRoot)) {
            for (Path tenantDirectory : tenantDirectories.filter(Files::isDirectory).toList()) {
                Path collectionsRoot = tenantDirectory.resolve("collections");
                if (!Files.isDirectory(collectionsRoot)) {
                    continue;
                }
                loadCollectionsUnderRoot(tenantDirectory.getFileName().toString(), collectionsRoot);
            }
        }
    }

    private void loadDefaultTenantCollections() throws IOException {
        try (Stream<Path> children = Files.list(dataDirectory)) {
            for (Path child : children.filter(Files::isDirectory).toList()) {
                if ("tenants".equals(child.getFileName().toString())) {
                    continue;
                }
                Path metadataFile = child.resolve("collection.json");
                if (!Files.exists(metadataFile)) {
                    continue;
                }
                CollectionDefinition definition = JsonSupport.read(Files.readAllBytes(metadataFile), CollectionDefinition.class);
                CollectionDefinition normalized = new CollectionDefinition(
                        definition.name(),
                        definition.dimension(),
                        definition.metric(),
                        definition.flushThresholdBytes(),
                        CollectionDefinition.DEFAULT_TENANT
                );
                collections.put(
                        new CollectionKey(normalized.tenantId(), normalized.name()),
                        EngineCollection.openExisting(normalized, CollectionPaths.of(dataDirectory, normalized.tenantId(), normalized.name()), searcher, observer)
                );
            }
        }
    }

    private void loadCollectionsUnderRoot(String tenantId, Path collectionsRoot) throws IOException {
        try (Stream<Path> children = Files.list(collectionsRoot)) {
            for (Path child : children.filter(Files::isDirectory).toList()) {
                Path metadataFile = child.resolve("collection.json");
                if (!Files.exists(metadataFile)) {
                    continue;
                }
                CollectionDefinition definition = JsonSupport.read(Files.readAllBytes(metadataFile), CollectionDefinition.class);
                CollectionDefinition normalized = new CollectionDefinition(
                        definition.name(),
                        definition.dimension(),
                        definition.metric(),
                        definition.flushThresholdBytes(),
                        tenantId
                );
                collections.put(
                        new CollectionKey(normalized.tenantId(), normalized.name()),
                        EngineCollection.openExisting(normalized, CollectionPaths.of(dataDirectory, normalized.tenantId(), normalized.name()), searcher, observer)
                );
            }
        }
    }

    private EngineCollection collection(String tenantId, String collectionName) {
        String normalizedTenantId = CollectionDefinition.normalizeTenantId(tenantId);
        EngineCollection collection = collections.get(new CollectionKey(normalizedTenantId, collectionName));
        if (collection == null) {
            throw new NotFoundException("Collection not found: " + normalizedTenantId + "/" + collectionName);
        }
        return collection;
    }

    private static CollectionStats previewCollectionStats(CollectionPaths paths, String tenantId) throws IOException {
        CollectionDefinition stored = JsonSupport.read(Files.readAllBytes(paths.metadataFile()), CollectionDefinition.class);
        CollectionDefinition definition = new CollectionDefinition(
                stored.name(),
                stored.dimension(),
                stored.metric(),
                stored.flushThresholdBytes(),
                tenantId
        );

        ConcurrentHashMap<String, PreviewState> latestStates = new ConcurrentHashMap<>();
        int segmentCount = 0;
        for (Path segmentFile : paths.listSegmentFiles()) {
            try (ImmutableSegment segment = ImmutableSegment.load(segmentFile, definition)) {
                segmentCount++;
                for (SegmentEntry entry : segment.entries()) {
                    latestStates.merge(
                            entry.id(),
                            new PreviewState(entry.sequence(), entry.tombstone()),
                            VectorDatabaseEngine::newerPreviewState
                    );
                }
            }
        }

        for (Path walFile : paths.listWalFiles()) {
            WalReplay.ReplayResult replayResult = WalReplay.readResult(walFile, definition);
            for (WalRecord record : replayResult.records()) {
                latestStates.merge(
                        record.id(),
                        new PreviewState(record.sequence(), record.tombstone()),
                        VectorDatabaseEngine::newerPreviewState
                );
            }
        }

        long liveVectors = latestStates.values().stream().filter(state -> !state.tombstone()).count();
        long tombstones = latestStates.size() - liveVectors;
        long storageBytes;
        try (Stream<Path> walk = Files.walk(paths.root())) {
            storageBytes = walk.filter(Files::isRegularFile).mapToLong(VectorDatabaseEngine::fileSize).sum();
        }

        return new CollectionStats(
                definition.name(),
                definition.dimension(),
                definition.metric(),
                liveVectors,
                tombstones,
                segmentCount,
                false,
                false,
                definition.tenantId(),
                storageBytes
        );
    }

    private static PreviewState newerPreviewState(PreviewState left, PreviewState right) {
        return left.sequence() >= right.sequence() ? left : right;
    }

    private static long fileSize(Path path) {
        try {
            return Files.size(path);
        } catch (IOException exception) {
            throw new UncheckedIOException("Failed to read file size for " + path, exception);
        }
    }

    private static void copyDirectory(Path sourceRoot, Path targetRoot) throws IOException {
        try (Stream<Path> walk = Files.walk(sourceRoot)) {
            for (Path path : walk.toList()) {
                Path relative = sourceRoot.relativize(path);
                Path target = targetRoot.resolve(relative);
                if (Files.isDirectory(path)) {
                    Files.createDirectories(target);
                } else {
                    Path parent = target.getParent();
                    if (parent != null) {
                        Files.createDirectories(parent);
                    }
                    Files.copy(path, target);
                }
            }
        }
    }

    private static void deleteDirectory(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private record CollectionKey(String tenantId, String name) {
        private CollectionKey(String tenantId, String name) {
            this.tenantId = CollectionDefinition.normalizeTenantId(tenantId);
            this.name = Objects.requireNonNull(name, "name");
        }
    }

    private record PreviewState(long sequence, boolean tombstone) {
    }
}
