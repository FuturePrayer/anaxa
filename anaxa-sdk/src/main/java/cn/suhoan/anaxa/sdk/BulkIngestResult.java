package cn.suhoan.anaxa.sdk;

import cn.suhoan.anaxa.common.model.CollectionStats;

import java.time.Duration;
import java.util.Objects;

/**
 * 高阶批量写入完成后的汇总结果。
 *
 * <p>相比只返回一次最终的 {@link CollectionStats}，这个结果还保留了“写了多少条、拆了几批、
 * 是否顺带 flush/compact”等流程级信息，便于业务方做导入日志和观测。
 */
public record BulkIngestResult(
        String collectionName,
        BulkIngestMode mode,
        long vectorsIngested,
        int batchesSent,
        boolean flushed,
        boolean compacted,
        Duration elapsed,
        CollectionStats finalStats
) {
    public BulkIngestResult {
        collectionName = Objects.requireNonNull(collectionName, "collectionName").trim();
        mode = Objects.requireNonNull(mode, "mode");
        elapsed = Objects.requireNonNull(elapsed, "elapsed");
        finalStats = Objects.requireNonNull(finalStats, "finalStats");
        if (collectionName.isEmpty()) {
            throw new IllegalArgumentException("collectionName must not be blank");
        }
        if (vectorsIngested < 0L) {
            throw new IllegalArgumentException("vectorsIngested must not be negative");
        }
        if (batchesSent < 0) {
            throw new IllegalArgumentException("batchesSent must not be negative");
        }
        if (elapsed.isNegative()) {
            throw new IllegalArgumentException("elapsed must not be negative");
        }
    }
}
