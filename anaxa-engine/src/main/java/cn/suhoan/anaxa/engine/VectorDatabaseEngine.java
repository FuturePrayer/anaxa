package cn.suhoan.anaxa.engine;

import cn.suhoan.anaxa.common.error.NotFoundException;
import cn.suhoan.anaxa.common.error.ValidationException;
import cn.suhoan.anaxa.common.json.JsonSupport;
import cn.suhoan.anaxa.common.model.CollectionDefinition;
import cn.suhoan.anaxa.common.model.CollectionStats;
import cn.suhoan.anaxa.common.model.CreateCollectionRequest;
import cn.suhoan.anaxa.common.model.SearchRequest;
import cn.suhoan.anaxa.common.model.SearchResponse;
import cn.suhoan.anaxa.common.model.UpsertVectorsRequest;
import cn.suhoan.anaxa.index.FlatSegmentIndexSearcher;
import cn.suhoan.anaxa.index.SegmentIndexSearcher;
import cn.suhoan.anaxa.storage.CollectionPaths;

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
    private final ConcurrentHashMap<String, EngineCollection> collections;

    public VectorDatabaseEngine(Path dataDirectory) throws IOException {
        this(dataDirectory, DEFAULT_FLUSH_THRESHOLD_BYTES, new FlatSegmentIndexSearcher());
    }

    public VectorDatabaseEngine(Path dataDirectory, long defaultFlushThresholdBytes) throws IOException {
        this(dataDirectory, defaultFlushThresholdBytes, new FlatSegmentIndexSearcher());
    }

    VectorDatabaseEngine(Path dataDirectory, long defaultFlushThresholdBytes, SegmentIndexSearcher searcher) throws IOException {
        this.dataDirectory = Objects.requireNonNull(dataDirectory, "dataDirectory");
        this.defaultFlushThresholdBytes = defaultFlushThresholdBytes;
        this.searcher = Objects.requireNonNull(searcher, "searcher");
        this.collections = new ConcurrentHashMap<>();
        Files.createDirectories(dataDirectory);
        loadExistingCollections();
    }

    public CollectionDefinition createCollection(CreateCollectionRequest request) {
        CollectionDefinition definition = request.toDefinition(defaultFlushThresholdBytes);
        CollectionPaths paths = CollectionPaths.of(dataDirectory, definition.name());
        if (collections.containsKey(definition.name()) || Files.exists(paths.metadataFile())) {
            throw new ValidationException("Collection already exists: " + definition.name());
        }

        EngineCollection collection;
        try {
            collection = EngineCollection.createNew(definition, paths, searcher);
        } catch (IOException exception) {
            throw new UncheckedIOException("Failed to create collection " + definition.name(), exception);
        }

        EngineCollection existing = collections.putIfAbsent(definition.name(), collection);
        if (existing != null) {
            collection.close();
            throw new ValidationException("Collection already exists: " + definition.name());
        }
        return definition;
    }

    public void upsert(String collectionName, UpsertVectorsRequest request) {
        collection(collectionName).upsert(request.vectors());
    }

    public SearchResponse search(String collectionName, SearchRequest request) {
        return collection(collectionName).search(request);
    }

    public CollectionStats stats(String collectionName) {
        return collection(collectionName).stats();
    }

    public List<CollectionStats> listCollections() {
        return collections.values().stream()
                .map(EngineCollection::stats)
                .sorted(Comparator.comparing(CollectionStats::name))
                .toList();
    }

    @Override
    public void close() {
        RuntimeException failure = null;
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

        if (failure != null) {
            throw failure;
        }
    }

    private void loadExistingCollections() throws IOException {
        try (Stream<Path> children = Files.list(dataDirectory)) {
            for (Path child : children.filter(Files::isDirectory).toList()) {
                Path metadataFile = child.resolve("collection.json");
                if (!Files.exists(metadataFile)) {
                    continue;
                }

                CollectionDefinition definition = JsonSupport.read(Files.readAllBytes(metadataFile), CollectionDefinition.class);
                CollectionPaths paths = CollectionPaths.of(dataDirectory, definition.name());
                collections.put(definition.name(), EngineCollection.openExisting(definition, paths, searcher));
            }
        }
    }

    private EngineCollection collection(String collectionName) {
        EngineCollection collection = collections.get(collectionName);
        if (collection == null) {
            throw new NotFoundException("Collection not found: " + collectionName);
        }
        return collection;
    }
}
