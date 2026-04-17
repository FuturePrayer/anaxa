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
    public static final int DEFAULT_UPSERT_BATCH_SIZE = 512;
    public static final int DEFAULT_PARTIAL_UPDATE_BATCH_SIZE = 256;
    public static final int DEFAULT_DELETION_BATCH_SIZE = 512;

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
