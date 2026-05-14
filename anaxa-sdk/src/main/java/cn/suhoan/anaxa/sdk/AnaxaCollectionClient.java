package cn.suhoan.anaxa.sdk;

import cn.suhoan.anaxa.common.model.BackupCollectionRequest;
import cn.suhoan.anaxa.common.model.CollectionStats;
import cn.suhoan.anaxa.common.model.CreateCollectionRequest;
import cn.suhoan.anaxa.common.model.DeleteVectorsRequest;
import cn.suhoan.anaxa.common.model.MetricType;
import cn.suhoan.anaxa.common.model.PartialUpdateVector;
import cn.suhoan.anaxa.common.model.PartialUpdateVectorsRequest;
import cn.suhoan.anaxa.common.model.RestoreCollectionRequest;
import cn.suhoan.anaxa.common.model.SearchRequest;
import cn.suhoan.anaxa.common.model.SearchResponse;
import cn.suhoan.anaxa.common.model.UpsertVector;
import cn.suhoan.anaxa.common.model.UpsertVectorsRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 绑定到单个 collection 的客户端。
 *
 * <p>把 collection 名预先绑定下来以后，调用代码会更接近业务语义，
 * 例如 {@code docs.search(...)}、{@code docs.flush()}、{@code docs.bulkUpsert(...)}。
 */
public final class AnaxaCollectionClient {
    private static final Logger log = LoggerFactory.getLogger(AnaxaCollectionClient.class);

    private final AnaxaClient client;
    private final String collectionName;

    AnaxaCollectionClient(AnaxaClient client, String collectionName) {
        this.client = Objects.requireNonNull(client, "client");
        String normalizedCollectionName = Objects.requireNonNull(collectionName, "collectionName").trim();
        if (normalizedCollectionName.isEmpty()) {
            throw new IllegalArgumentException("collectionName must not be blank");
        }
        this.collectionName = normalizedCollectionName;
    }

    /**
     * 当前绑定的 collection 名。
     *
     * @return bound collection name
     */
    public String name() {
        return collectionName;
    }

    /**
     * 查询 collection 统计信息。
     *
     * @return collection statistics
     * @throws IOException when the HTTP request fails
     * @throws InterruptedException when the request thread is interrupted
     */
    public CollectionStats stats() throws IOException, InterruptedException {
        return client.getCollection(collectionName);
    }

    /**
     * 直接创建 collection。
     *
     * <p>请求里的 {@code name} 必须与当前绑定的 collection 一致，
     * 这样可以避免“对象名是 docs，请求体里却写了 kb-docs”这类隐蔽错误。
     *
     * @param request create-collection request
     * @return created collection statistics
     * @throws IOException when the HTTP request fails
     * @throws InterruptedException when the request thread is interrupted
     */
    public CollectionStats create(CreateCollectionRequest request) throws IOException, InterruptedException {
        validateCreateRequest(request);
        return client.createCollection(request);
    }

    /**
     * 确保当前 collection 存在。
     *
     * <p>如果 collection 尚不存在，就直接创建；如果已经存在，则回读当前配置，
     * 并校验维度与度量方式是否和调用方期望一致。
     *
     * @param request expected collection definition
     * @return existing or created collection statistics
     * @throws IOException when the HTTP request fails
     * @throws InterruptedException when the request thread is interrupted
     */
    public CollectionStats ensureExists(CreateCollectionRequest request) throws IOException, InterruptedException {
        validateCreateRequest(request);
        try {
            return client.createCollection(request);
        } catch (AnaxaClientException exception) {
            if (!isCollectionAlreadyExists(exception)) {
                throw exception;
            }
            CollectionStats existing = stats();
            MetricType expectedMetric = request.metric() == null ? MetricType.COSINE : request.metric();
            if (existing.dimension() != request.dimension()) {
                throw new IllegalStateException(
                        "Existing collection dimension does not match request: expected %d but got %d"
                                .formatted(request.dimension(), existing.dimension())
                );
            }
            if (existing.metric() != expectedMetric) {
                throw new IllegalStateException(
                        "Existing collection metric does not match request: expected %s but got %s"
                                .formatted(expectedMetric, existing.metric())
                );
            }
            return existing;
        }
    }

    /**
     * 使用 JSON 数组方式写入一批向量。
     *
     * @param vectors vectors to upsert
     * @return updated collection statistics
     * @throws IOException when the HTTP request fails
     * @throws InterruptedException when the request thread is interrupted
     */
    public CollectionStats upsertJson(Collection<UpsertVector> vectors) throws IOException, InterruptedException {
        return client.sendJson(
                "POST",
                client.path("collections", collectionName, "vectors"),
                new UpsertVectorsRequest(List.copyOf(Objects.requireNonNull(vectors, "vectors"))),
                CollectionStats.class
        );
    }

    /**
     * 使用 NDJSON 方式写入一批向量。
     *
     * @param vectors vectors to upsert
     * @return updated collection statistics
     * @throws IOException when the HTTP request fails
     * @throws InterruptedException when the request thread is interrupted
     */
    public CollectionStats upsertNdjson(Iterable<UpsertVector> vectors) throws IOException, InterruptedException {
        return client.sendNdjsonUpsert(collectionName, Objects.requireNonNull(vectors, "vectors"));
    }

    /**
     * 使用二进制批量协议写入一批向量。
     *
     * <p>SDK 会先回读 collection 维度，用它来构建 binary header，
     * 这样调用方不需要再手动维护一份维度参数。
     *
     * @param vectors vectors to upsert
     * @return updated collection statistics
     * @throws IOException when the HTTP request fails
     * @throws InterruptedException when the request thread is interrupted
     */
    public CollectionStats upsertBinary(Iterable<UpsertVector> vectors) throws IOException, InterruptedException {
        int dimension = stats().dimension();
        return client.sendBinaryUpsert(collectionName, dimension, Objects.requireNonNull(vectors, "vectors"));
    }

    /**
     * 执行 payload-only partial update。
     *
     * @param updates payload-only updates to apply
     * @return updated collection statistics
     * @throws IOException when the HTTP request fails
     * @throws InterruptedException when the request thread is interrupted
     */
    public CollectionStats partialUpdate(Collection<PartialUpdateVector> updates) throws IOException, InterruptedException {
        return client.sendJson(
                "PATCH",
                client.path("collections", collectionName, "vectors"),
                new PartialUpdateVectorsRequest(List.copyOf(Objects.requireNonNull(updates, "updates"))),
                CollectionStats.class
        );
    }

    /**
     * 批量删除向量。
     *
     * @param ids vector ids to delete
     * @return updated collection statistics
     * @throws IOException when the HTTP request fails
     * @throws InterruptedException when the request thread is interrupted
     */
    public CollectionStats delete(Collection<String> ids) throws IOException, InterruptedException {
        return client.sendJson(
                "POST",
                client.path("collections", collectionName, "deletions"),
                new DeleteVectorsRequest(List.copyOf(Objects.requireNonNull(ids, "ids"))),
                CollectionStats.class
        );
    }

    /**
     * 执行一次向量检索。
     *
     * @param request search request
     * @return search response
     * @throws IOException when the HTTP request fails
     * @throws InterruptedException when the request thread is interrupted
     */
    public SearchResponse search(SearchRequest request) throws IOException, InterruptedException {
        return client.sendJson(
                "POST",
                client.path("collections", collectionName, "search"),
                Objects.requireNonNull(request, "request"),
                SearchResponse.class
        );
    }

    /**
     * 使用最简参数执行搜索。
     *
     * @param vector query vector
     * @param topK number of hits to return
     * @return search response
     * @throws IOException when the HTTP request fails
     * @throws InterruptedException when the request thread is interrupted
     */
    public SearchResponse search(float[] vector, int topK) throws IOException, InterruptedException {
        return search(new SearchRequest(vector, topK, Map.of()));
    }

    /**
     * 使用原始 payload filter Map 执行搜索。
     *
     * @param vector query vector
     * @param topK number of hits to return
     * @param filter payload filter map
     * @return search response
     * @throws IOException when the HTTP request fails
     * @throws InterruptedException when the request thread is interrupted
     */
    public SearchResponse search(float[] vector, int topK, Map<String, Object> filter)
            throws IOException, InterruptedException {
        return search(new SearchRequest(vector, topK, filter));
    }

    /**
     * 使用 SDK 提供的过滤 DSL 执行搜索。
     *
     * @param vector query vector
     * @param topK number of hits to return
     * @param filterBuilder payload filter builder
     * @return search response
     * @throws IOException when the HTTP request fails
     * @throws InterruptedException when the request thread is interrupted
     */
    public SearchResponse search(float[] vector, int topK, PayloadFilterBuilder filterBuilder)
            throws IOException, InterruptedException {
        return search(new SearchRequest(vector, topK, filterBuilder == null ? Map.of() : filterBuilder.build()));
    }

    /**
     * 手工触发 flush。
     *
     * @return updated collection statistics
     * @throws IOException when the HTTP request fails
     * @throws InterruptedException when the request thread is interrupted
     */
    public CollectionStats flush() throws IOException, InterruptedException {
        return client.sendJson("POST", client.path("collections", collectionName, "flush"), null, CollectionStats.class);
    }

    /**
     * 手工触发 compaction。
     *
     * @return updated collection statistics
     * @throws IOException when the HTTP request fails
     * @throws InterruptedException when the request thread is interrupted
     */
    public CollectionStats compact() throws IOException, InterruptedException {
        return client.sendJson("POST", client.path("collections", collectionName, "compact"), null, CollectionStats.class);
    }

    /**
     * 备份当前 collection。
     *
     * @param backupId backup id to create
     * @return updated collection statistics
     * @throws IOException when the HTTP request fails
     * @throws InterruptedException when the request thread is interrupted
     */
    public CollectionStats backup(String backupId) throws IOException, InterruptedException {
        return client.sendJson(
                "POST",
                client.path("collections", collectionName, "backup"),
                new BackupCollectionRequest(backupId),
                CollectionStats.class
        );
    }

    /**
     * 从指定 backup 恢复到当前 collection 名。
     *
     * <p>适合“把旧备份恢复到一个新 collection 做验证”这种操作。
     *
     * @param backupId backup id to restore from
     * @param sourceCollectionName collection name inside the backup
     * @return restored collection statistics
     * @throws IOException when the HTTP request fails
     * @throws InterruptedException when the request thread is interrupted
     */
    public CollectionStats restoreFromBackup(String backupId, String sourceCollectionName)
            throws IOException, InterruptedException {
        return client.sendJson(
                "POST",
                client.path("backups", backupId, "restore"),
                new RestoreCollectionRequest(sourceCollectionName, collectionName),
                CollectionStats.class
        );
    }

    /**
     * 高阶批量写入 API。
     *
     * <p>这个方法会自动按 batch 切分数据，并根据 {@link BulkIngestOptions#mode()}
     * 选择 JSON / NDJSON / binary 三种传输方式之一。必要时还会在尾部补一次 flush / compact。
     *
     * @param vectors vectors to upsert
     * @param options bulk ingest options
     * @return bulk ingest result
     * @throws IOException when the HTTP request fails
     * @throws InterruptedException when the request thread is interrupted
     */
    public BulkIngestResult bulkUpsert(Iterable<UpsertVector> vectors, BulkIngestOptions options)
            throws IOException, InterruptedException {
        Objects.requireNonNull(vectors, "vectors");
        BulkIngestOptions resolvedOptions = Objects.requireNonNull(options, "options");

        long startedAtNanos = System.nanoTime();
        int dimension = resolvedOptions.mode() == BulkIngestMode.BINARY ? stats().dimension() : -1;
        int batchCount = 0;
        long vectorCount = 0L;
        CollectionStats finalStats = null;

        log.info(
                "开始批量写入: collection={} mode={} batchSize={} flushAfterWrite={} compactAfterFlush={}",
                collectionName,
                resolvedOptions.mode(),
                resolvedOptions.batchSize(),
                resolvedOptions.flushAfterWrite(),
                resolvedOptions.compactAfterFlush()
        );

        Iterator<UpsertVector> iterator = vectors.iterator();
        while (iterator.hasNext()) {
            List<UpsertVector> batch = takeBatch(iterator, resolvedOptions.batchSize());
            finalStats = switch (resolvedOptions.mode()) {
                case JSON -> upsertJson(batch);
                case NDJSON -> upsertNdjson(batch);
                case BINARY -> client.sendBinaryUpsert(collectionName, dimension, batch);
            };
            batchCount++;
            vectorCount += batch.size();
            log.debug(
                    "批量写入子批次完成: collection={} mode={} batchIndex={} batchSize={}",
                    collectionName,
                    resolvedOptions.mode(),
                    batchCount,
                    batch.size()
            );
        }

        if (vectorCount == 0L) {
            throw new IllegalArgumentException("vectors must not be empty");
        }

        boolean flushed = false;
        boolean compacted = false;
        if (resolvedOptions.flushAfterWrite() || resolvedOptions.compactAfterFlush()) {
            finalStats = flush();
            flushed = true;
        }
        if (resolvedOptions.compactAfterFlush()) {
            finalStats = compact();
            compacted = true;
        }

        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAtNanos);
        log.info(
                "批量写入完成: collection={} mode={} vectors={} batches={} flushed={} compacted={} elapsedMs={}",
                collectionName,
                resolvedOptions.mode(),
                vectorCount,
                batchCount,
                flushed,
                compacted,
                elapsed.toMillis()
        );
        return new BulkIngestResult(
                collectionName,
                resolvedOptions.mode(),
                vectorCount,
                batchCount,
                flushed,
                compacted,
                elapsed,
                Objects.requireNonNull(finalStats, "finalStats")
        );
    }

    /**
     * 面向“首批导入后立即切读”的高阶 API。
     *
     * <p>它会先确保 collection 存在，再执行批量导入，并且无论调用方是否显式开启，
     * 都会在末尾补一次 flush，让数据尽快进入更稳定的读路径。
     *
     * @param request expected collection definition
     * @param vectors vectors to ingest
     * @param options bulk ingest options
     * @return bulk ingest result
     * @throws IOException when the HTTP request fails
     * @throws InterruptedException when the request thread is interrupted
     */
    public BulkIngestResult loadForServing(
            CreateCollectionRequest request,
            Iterable<UpsertVector> vectors,
            BulkIngestOptions options
    ) throws IOException, InterruptedException {
        ensureExists(request);
        BulkIngestOptions effectiveOptions = Objects.requireNonNull(options, "options").withFlushAfterWrite(true);
        return bulkUpsert(vectors, effectiveOptions);
    }

    /**
     * 面向“文档增删改同步”的高阶 API。
     *
     * <p>推荐把一次增量同步拆成：
     *
     * <ol>
     *   <li>upsert 正文变化的文档</li>
     *   <li>partial update 只改 metadata 的文档</li>
     *   <li>delete 已删除的文档</li>
     *   <li>如有需要，再 flush / compact</li>
     * </ol>
     *
     * @param plan synchronization plan
     * @return synchronization result
     * @throws IOException when the HTTP request fails
     * @throws InterruptedException when the request thread is interrupted
     */
    public CollectionSyncResult synchronize(CollectionSyncPlan plan) throws IOException, InterruptedException {
        CollectionSyncPlan resolvedPlan = Objects.requireNonNull(plan, "plan");
        long startedAtNanos = System.nanoTime();

        log.info(
                "开始执行 collection 同步: collection={} upserts={} partialUpdates={} deletions={} flushAfterSync={} compactAfterSync={}",
                collectionName,
                resolvedPlan.upserts().size(),
                resolvedPlan.partialUpdates().size(),
                resolvedPlan.deletions().size(),
                resolvedPlan.flushAfterSync(),
                resolvedPlan.compactAfterSync()
        );

        CollectionStats finalStats = null;
        int upsertBatches = 0;
        int partialUpdateBatches = 0;
        int deletionBatches = 0;

        if (!resolvedPlan.upserts().isEmpty()) {
            int dimension = resolvedPlan.upsertMode() == BulkIngestMode.BINARY ? stats().dimension() : -1;
            Iterator<UpsertVector> iterator = resolvedPlan.upserts().iterator();
            while (iterator.hasNext()) {
                List<UpsertVector> batch = takeBatch(iterator, resolvedPlan.upsertBatchSize());
                finalStats = switch (resolvedPlan.upsertMode()) {
                    case JSON -> upsertJson(batch);
                    case NDJSON -> upsertNdjson(batch);
                    case BINARY -> client.sendBinaryUpsert(collectionName, dimension, batch);
                };
                upsertBatches++;
            }
        }

        if (!resolvedPlan.partialUpdates().isEmpty()) {
            Iterator<PartialUpdateVector> iterator = resolvedPlan.partialUpdates().iterator();
            while (iterator.hasNext()) {
                finalStats = partialUpdate(takeBatch(iterator, resolvedPlan.partialUpdateBatchSize()));
                partialUpdateBatches++;
            }
        }

        if (!resolvedPlan.deletions().isEmpty()) {
            Iterator<String> iterator = resolvedPlan.deletions().iterator();
            while (iterator.hasNext()) {
                finalStats = delete(takeBatch(iterator, resolvedPlan.deletionBatchSize()));
                deletionBatches++;
            }
        }

        boolean flushed = false;
        boolean compacted = false;
        if (resolvedPlan.flushAfterSync() || resolvedPlan.compactAfterSync()) {
            finalStats = flush();
            flushed = true;
        }
        if (resolvedPlan.compactAfterSync()) {
            finalStats = compact();
            compacted = true;
        }
        if (finalStats == null) {
            finalStats = stats();
        }

        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAtNanos);
        log.info(
                "collection 同步完成: collection={} upserts={} partialUpdates={} deletions={} upsertBatches={} partialUpdateBatches={} deletionBatches={} flushed={} compacted={} elapsedMs={}",
                collectionName,
                resolvedPlan.upserts().size(),
                resolvedPlan.partialUpdates().size(),
                resolvedPlan.deletions().size(),
                upsertBatches,
                partialUpdateBatches,
                deletionBatches,
                flushed,
                compacted,
                elapsed.toMillis()
        );
        return new CollectionSyncResult(
                collectionName,
                resolvedPlan.upserts().size(),
                resolvedPlan.partialUpdates().size(),
                resolvedPlan.deletions().size(),
                upsertBatches,
                partialUpdateBatches,
                deletionBatches,
                flushed,
                compacted,
                elapsed,
                finalStats
        );
    }

    private void validateCreateRequest(CreateCollectionRequest request) {
        Objects.requireNonNull(request, "request");
        if (!collectionName.equals(request.name())) {
            throw new IllegalArgumentException(
                    "CreateCollectionRequest.name must match bound collection name: expected %s but got %s"
                            .formatted(collectionName, request.name())
            );
        }
    }

    private static boolean isCollectionAlreadyExists(AnaxaClientException exception) {
        return exception.statusCode() == 400 && exception.getMessage().contains("Collection already exists:");
    }

    private static <T> List<T> takeBatch(Iterator<T> iterator, int batchSize) {
        ArrayList<T> batch = new ArrayList<>(batchSize);
        while (iterator.hasNext() && batch.size() < batchSize) {
            batch.add(iterator.next());
        }
        return batch;
    }
}
