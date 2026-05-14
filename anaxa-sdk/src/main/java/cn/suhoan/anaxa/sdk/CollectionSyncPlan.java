package cn.suhoan.anaxa.sdk;

import cn.suhoan.anaxa.common.model.PartialUpdateVector;
import cn.suhoan.anaxa.common.model.UpsertVector;

import java.util.List;
import java.util.Objects;

/**
 * 文档同步型场景的高阶执行计划。
 *
 * <p>典型用法是把一次知识库增量同步拆成三类动作：
 *
 * <ul>
 *   <li>upsert：新增文档，或正文变化后重算 embedding 的文档</li>
 *   <li>partial update：只改 payload / metadata，不重传向量</li>
 *   <li>delete：文档被删除或移出索引范围</li>
 * </ul>
 *
 * <p>最后再决定是否自动 flush / compact，从而把“推荐流程”直接固化到 SDK 层。
 *
 * @param upserts vectors to upsert
 * @param partialUpdates payload-only updates to apply
 * @param deletions vector ids to delete
 * @param upsertBatchSize maximum upsert batch size
 * @param upsertMode transport mode for upserts
 * @param partialUpdateBatchSize maximum partial-update batch size
 * @param deletionBatchSize maximum deletion batch size
 * @param flushAfterSync whether to flush after mutations
 * @param compactAfterSync whether to compact after the optional flush
 */
public record CollectionSyncPlan(
        List<UpsertVector> upserts,
        List<PartialUpdateVector> partialUpdates,
        List<String> deletions,
        int upsertBatchSize,
        BulkIngestMode upsertMode,
        int partialUpdateBatchSize,
        int deletionBatchSize,
        boolean flushAfterSync,
        boolean compactAfterSync
) {
    /** Default upsert batch size. */
    public static final int DEFAULT_UPSERT_BATCH_SIZE = 512;
    /** Default partial-update batch size. */
    public static final int DEFAULT_PARTIAL_UPDATE_BATCH_SIZE = 256;
    /** Default deletion batch size. */
    public static final int DEFAULT_DELETION_BATCH_SIZE = 512;

    /**
     * Creates a collection sync plan.
     */
    public CollectionSyncPlan {
        upserts = List.copyOf(Objects.requireNonNull(upserts, "upserts"));
        partialUpdates = List.copyOf(Objects.requireNonNull(partialUpdates, "partialUpdates"));
        deletions = List.copyOf(Objects.requireNonNull(deletions, "deletions").stream()
                .map(id -> Objects.requireNonNull(id, "id").trim())
                .toList());
        if (upserts.isEmpty() && partialUpdates.isEmpty() && deletions.isEmpty()) {
            throw new IllegalArgumentException("At least one mutation must be provided");
        }
        if (deletions.stream().anyMatch(String::isEmpty)) {
            throw new IllegalArgumentException("Deletion id must not be blank");
        }
        if (upsertBatchSize <= 0) {
            throw new IllegalArgumentException("upsertBatchSize must be positive");
        }
        upsertMode = Objects.requireNonNull(upsertMode, "upsertMode");
        if (partialUpdateBatchSize <= 0) {
            throw new IllegalArgumentException("partialUpdateBatchSize must be positive");
        }
        if (deletionBatchSize <= 0) {
            throw new IllegalArgumentException("deletionBatchSize must be positive");
        }
    }

    /**
     * 用默认批次和默认 binary upsert 模式创建同步计划。
     *
     * @param upserts vectors to upsert
     * @param partialUpdates payload-only updates to apply
     * @param deletions vector ids to delete
     * @return sync plan using default batch settings
     */
    public static CollectionSyncPlan of(
            List<UpsertVector> upserts,
            List<PartialUpdateVector> partialUpdates,
            List<String> deletions
    ) {
        return new CollectionSyncPlan(
                upserts,
                partialUpdates,
                deletions,
                DEFAULT_UPSERT_BATCH_SIZE,
                BulkIngestMode.BINARY,
                DEFAULT_PARTIAL_UPDATE_BATCH_SIZE,
                DEFAULT_DELETION_BATCH_SIZE,
                false,
                false
        );
    }

    /**
     * Returns a copy with a different upsert batch size.
     *
     * @param newBatchSize new upsert batch size
     * @return updated sync plan
     */
    public CollectionSyncPlan withUpsertBatchSize(int newBatchSize) {
        return new CollectionSyncPlan(
                upserts,
                partialUpdates,
                deletions,
                newBatchSize,
                upsertMode,
                partialUpdateBatchSize,
                deletionBatchSize,
                flushAfterSync,
                compactAfterSync
        );
    }

    /**
     * Returns a copy with a different upsert transport mode.
     *
     * @param newMode new upsert mode
     * @return updated sync plan
     */
    public CollectionSyncPlan withUpsertMode(BulkIngestMode newMode) {
        return new CollectionSyncPlan(
                upserts,
                partialUpdates,
                deletions,
                upsertBatchSize,
                newMode,
                partialUpdateBatchSize,
                deletionBatchSize,
                flushAfterSync,
                compactAfterSync
        );
    }

    /**
     * Returns a copy with a different partial-update batch size.
     *
     * @param newBatchSize new partial-update batch size
     * @return updated sync plan
     */
    public CollectionSyncPlan withPartialUpdateBatchSize(int newBatchSize) {
        return new CollectionSyncPlan(
                upserts,
                partialUpdates,
                deletions,
                upsertBatchSize,
                upsertMode,
                newBatchSize,
                deletionBatchSize,
                flushAfterSync,
                compactAfterSync
        );
    }

    /**
     * Returns a copy with a different deletion batch size.
     *
     * @param newBatchSize new deletion batch size
     * @return updated sync plan
     */
    public CollectionSyncPlan withDeletionBatchSize(int newBatchSize) {
        return new CollectionSyncPlan(
                upserts,
                partialUpdates,
                deletions,
                upsertBatchSize,
                upsertMode,
                partialUpdateBatchSize,
                newBatchSize,
                flushAfterSync,
                compactAfterSync
        );
    }

    /**
     * Returns a copy with flush-after-sync enabled or disabled.
     *
     * @param enabled whether to flush after sync
     * @return updated sync plan
     */
    public CollectionSyncPlan withFlushAfterSync(boolean enabled) {
        return new CollectionSyncPlan(
                upserts,
                partialUpdates,
                deletions,
                upsertBatchSize,
                upsertMode,
                partialUpdateBatchSize,
                deletionBatchSize,
                enabled,
                compactAfterSync
        );
    }

    /**
     * Returns a copy with compact-after-sync enabled or disabled.
     *
     * @param enabled whether to compact after sync
     * @return updated sync plan
     */
    public CollectionSyncPlan withCompactAfterSync(boolean enabled) {
        return new CollectionSyncPlan(
                upserts,
                partialUpdates,
                deletions,
                upsertBatchSize,
                upsertMode,
                partialUpdateBatchSize,
                deletionBatchSize,
                flushAfterSync,
                enabled
        );
    }
}
