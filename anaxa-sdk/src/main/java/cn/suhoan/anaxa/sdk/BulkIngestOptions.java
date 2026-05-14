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
 *
 * @param batchSize maximum number of vectors per batch
 * @param mode transport format used for each batch
 * @param flushAfterWrite whether to flush after all writes complete
 * @param compactAfterFlush whether to compact after the optional flush
 */
public record BulkIngestOptions(
        int batchSize,
        BulkIngestMode mode,
        boolean flushAfterWrite,
        boolean compactAfterFlush
) {
    /** Default number of vectors per batch. */
    public static final int DEFAULT_BATCH_SIZE = 512;

    /**
     * Creates bulk ingest options.
     */
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
     *
     * @return default bulk ingest options
     */
    public static BulkIngestOptions defaults() {
        return new BulkIngestOptions(DEFAULT_BATCH_SIZE, BulkIngestMode.BINARY, false, false);
    }

    /**
     * Returns a copy with a different batch size.
     *
     * @param newBatchSize new batch size
     * @return updated options
     */
    public BulkIngestOptions withBatchSize(int newBatchSize) {
        return new BulkIngestOptions(newBatchSize, mode, flushAfterWrite, compactAfterFlush);
    }

    /**
     * Returns a copy with a different transport mode.
     *
     * @param newMode new transport mode
     * @return updated options
     */
    public BulkIngestOptions withMode(BulkIngestMode newMode) {
        return new BulkIngestOptions(batchSize, newMode, flushAfterWrite, compactAfterFlush);
    }

    /**
     * Returns a copy with flush-after-write enabled or disabled.
     *
     * @param enabled whether to flush after writing
     * @return updated options
     */
    public BulkIngestOptions withFlushAfterWrite(boolean enabled) {
        return new BulkIngestOptions(batchSize, mode, enabled, compactAfterFlush);
    }

    /**
     * Returns a copy with compact-after-flush enabled or disabled.
     *
     * @param enabled whether to compact after flushing
     * @return updated options
     */
    public BulkIngestOptions withCompactAfterFlush(boolean enabled) {
        return new BulkIngestOptions(batchSize, mode, flushAfterWrite, enabled);
    }
}
