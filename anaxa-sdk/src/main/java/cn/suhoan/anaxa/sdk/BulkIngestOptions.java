package cn.suhoan.anaxa.sdk;

import java.util.Objects;

/**
 * 高阶批量写入 API 的执行参数。
 *
 * <p>这个类型服务于两类典型场景：
 *
 * <ol>
 *   <li>批量导入：重点关注吞吐，通常会选择 binary + 较大的 batch。</li>
 *   <li>导入后切读：除了写入本身，还希望在尾部自动补一次 flush。</li>
 * </ol>
 */
public record BulkIngestOptions(
        int batchSize,
        BulkIngestMode mode,
        boolean flushAfterWrite,
        boolean compactAfterFlush
) {
    public static final int DEFAULT_BATCH_SIZE = 512;

    public BulkIngestOptions {
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batchSize must be positive");
        }
        mode = Objects.requireNonNull(mode, "mode");
    }

    /**
     * 返回一组偏保守的默认值：
     *
     * <ul>
     *   <li>batchSize = 512</li>
     *   <li>mode = BINARY</li>
     *   <li>flushAfterWrite = false</li>
     *   <li>compactAfterFlush = false</li>
     * </ul>
     */
    public static BulkIngestOptions defaults() {
        return new BulkIngestOptions(DEFAULT_BATCH_SIZE, BulkIngestMode.BINARY, false, false);
    }

    public BulkIngestOptions withBatchSize(int newBatchSize) {
        return new BulkIngestOptions(newBatchSize, mode, flushAfterWrite, compactAfterFlush);
    }

    public BulkIngestOptions withMode(BulkIngestMode newMode) {
        return new BulkIngestOptions(batchSize, newMode, flushAfterWrite, compactAfterFlush);
    }

    public BulkIngestOptions withFlushAfterWrite(boolean enabled) {
        return new BulkIngestOptions(batchSize, mode, enabled, compactAfterFlush);
    }

    public BulkIngestOptions withCompactAfterFlush(boolean enabled) {
        return new BulkIngestOptions(batchSize, mode, flushAfterWrite, enabled);
    }
}
