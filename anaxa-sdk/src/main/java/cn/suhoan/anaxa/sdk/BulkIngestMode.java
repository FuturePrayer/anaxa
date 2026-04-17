package cn.suhoan.anaxa.sdk;

/**
 * SDK 在批量写入时可选择的传输格式。
 */
public enum BulkIngestMode {
    /**
     * 使用普通 JSON 数组封装写入。
     *
     * <p>优点是最直观、最容易调试；缺点是序列化体积通常最大。
     */
    JSON,

    /**
     * 使用 NDJSON 逐行传输。
     *
     * <p>适合从文本流、文件流逐步组装导入数据的场景。
     */
    NDJSON,

    /**
     * 使用 AnaxaDB 的二进制批量协议。
     *
     * <p>这是推荐的高吞吐导入模式，能显著降低 JSON 解析开销。
     */
    BINARY
}
