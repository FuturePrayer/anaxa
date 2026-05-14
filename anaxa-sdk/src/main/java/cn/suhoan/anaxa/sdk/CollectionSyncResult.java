package cn.suhoan.anaxa.sdk;

import cn.suhoan.anaxa.common.model.CollectionStats;

import java.time.Duration;
import java.util.Objects;

/**
 * 一次“文档同步流程”执行完后的结果。
 *
 * @param collectionName collection name
 * @param upsertsApplied number of upserted vectors
 * @param partialUpdatesApplied number of partial updates applied
 * @param deletionsApplied number of deletions applied
 * @param upsertBatchesSent number of upsert batches sent
 * @param partialUpdateBatchesSent number of partial update batches sent
 * @param deletionBatchesSent number of deletion batches sent
 * @param flushed whether a flush was performed
 * @param compacted whether compaction was performed
 * @param elapsed elapsed time
 * @param finalStats final collection statistics
 */
public record CollectionSyncResult(
        String collectionName,
        long upsertsApplied,
        long partialUpdatesApplied,
        long deletionsApplied,
        int upsertBatchesSent,
        int partialUpdateBatchesSent,
        int deletionBatchesSent,
        boolean flushed,
        boolean compacted,
        Duration elapsed,
        CollectionStats finalStats
) {
    /**
     * Creates a collection sync result.
     */
    public CollectionSyncResult {
        collectionName = Objects.requireNonNull(collectionName, "collectionName").trim();
        elapsed = Objects.requireNonNull(elapsed, "elapsed");
        finalStats = Objects.requireNonNull(finalStats, "finalStats");
        if (collectionName.isEmpty()) {
            throw new IllegalArgumentException("collectionName must not be blank");
        }
        if (upsertsApplied < 0L || partialUpdatesApplied < 0L || deletionsApplied < 0L) {
            throw new IllegalArgumentException("Applied counts must not be negative");
        }
        if (upsertBatchesSent < 0 || partialUpdateBatchesSent < 0 || deletionBatchesSent < 0) {
            throw new IllegalArgumentException("Batch counts must not be negative");
        }
        if (elapsed.isNegative()) {
            throw new IllegalArgumentException("elapsed must not be negative");
        }
    }
}
