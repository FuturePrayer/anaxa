package cn.suhoan.anaxa.sdk.examples;

import cn.suhoan.anaxa.common.model.CreateCollectionRequest;
import cn.suhoan.anaxa.common.model.MetricType;
import cn.suhoan.anaxa.common.model.PartialUpdateVector;
import cn.suhoan.anaxa.common.model.SearchResponse;
import cn.suhoan.anaxa.common.model.UpsertVector;
import cn.suhoan.anaxa.sdk.AnaxaClient;
import cn.suhoan.anaxa.sdk.BulkIngestMode;
import cn.suhoan.anaxa.sdk.BulkIngestOptions;
import cn.suhoan.anaxa.sdk.CollectionSyncPlan;
import cn.suhoan.anaxa.sdk.PayloadFilterBuilder;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * SDK 使用示例。
 *
 * <p>这些方法刻意保持为“可编译的普通 Java 代码”，调用方可以直接复制到自己的项目里按需裁剪。
 * 示例本身不会在类加载时自动执行，只有显式调用时才会发起网络请求。
 */
@SuppressWarnings("unused")
public final class AnaxaSdkExamples {
    private AnaxaSdkExamples() {
    }

    /**
     * 最基础的 CRUD + 搜索示例。
     *
     * @throws IOException when the HTTP request fails
     * @throws InterruptedException when the request thread is interrupted
     */
    public static void basicOperations() throws IOException, InterruptedException {
        // SDK 本身只依赖 slf4j-api；日志实现由业务方自己在应用里提供。
        try (AnaxaClient client = AnaxaClient.builder()
                .baseUri("http://127.0.0.1:30720")
                .apiKey("writer-secret")
                .defaultTenantId("team-a")
                .build()) {

            // 1. 创建或确认 collection。
            client.collection("docs").ensureExists(
                    new CreateCollectionRequest("docs", 3, MetricType.COSINE, 1_048_576L)
            );

            // 2. 写入一批向量。
            client.collection("docs").upsertJson(List.of(
                    new UpsertVector(
                            "doc-1",
                            new float[]{1.0f, 0.0f, 0.0f},
                            Map.of("path", "guide/intro.md", "tenant", "team-a")
                    ),
                    new UpsertVector(
                            "doc-2",
                            new float[]{0.0f, 1.0f, 0.0f},
                            Map.of("path", "guide/faq.md", "tenant", "team-a")
                    )
            ));

            // 3. 对只改 metadata 的文档，走 partial update，不用重传向量。
            client.collection("docs").partialUpdate(List.of(
                    new PartialUpdateVector(
                            "doc-1",
                            Map.of(
                                    "title", "Intro v2",
                                    "meta", Map.of("published", true)
                            )
                    )
            ));

            // 4. 用过滤 DSL 执行搜索。
            SearchResponse response = client.collection("docs").search(
                    new float[]{1.0f, 0.0f, 0.0f},
                    5,
                    PayloadFilterBuilder.filter()
                            .eq("tenant", "team-a")
                            .contains("path", "guide")
            );

            // 这里故意不打印结果，只保留一个变量，便于调用方直接查看 IDE 类型提示。
            int hitCount = response.hits().size();
            if (hitCount < 0) {
                throw new IllegalStateException("Impossible branch");
            }
        }
    }

    /**
     * 面向“首批导入后马上切读”的示例。
     *
     * @throws IOException when the HTTP request fails
     * @throws InterruptedException when the request thread is interrupted
     */
    public static void bulkLoadAndPrepareForServing() throws IOException, InterruptedException {
        try (AnaxaClient client = AnaxaClient.builder()
                .baseUri("http://127.0.0.1:30720")
                .apiKey("writer-secret")
                .defaultTenantId("team-a")
                .build()) {

            List<UpsertVector> firstLoad = List.of(
                    new UpsertVector("chunk-1", new float[]{1.0f, 0.0f, 0.0f}, Map.of("path", "kb/a.md")),
                    new UpsertVector("chunk-2", new float[]{0.0f, 1.0f, 0.0f}, Map.of("path", "kb/b.md")),
                    new UpsertVector("chunk-3", new float[]{0.0f, 0.0f, 1.0f}, Map.of("path", "kb/c.md"))
            );

            // 这个高阶 API 会先 ensure collection，再按推荐流程批量导入，并在结尾自动 flush。
            client.collection("markdown-kb").loadForServing(
                    new CreateCollectionRequest("markdown-kb", 3, MetricType.COSINE, 8L * 1024 * 1024),
                    firstLoad,
                    BulkIngestOptions.defaults()
                            .withMode(BulkIngestMode.BINARY)
                            .withBatchSize(256)
            );
        }
    }

    /**
     * 面向“知识库增量同步”的示例。
     *
     * @throws IOException when the HTTP request fails
     * @throws InterruptedException when the request thread is interrupted
     */
    public static void synchronizeKnowledgeBase() throws IOException, InterruptedException {
        try (AnaxaClient client = AnaxaClient.builder()
                .baseUri("http://127.0.0.1:30720")
                .apiKey("writer-secret")
                .defaultTenantId("team-a")
                .build()) {

            // 一次同步里同时包含：
            // 1. 新增/重建 embedding 的文档
            // 2. 只改 metadata 的文档
            // 3. 已删除的文档
            CollectionSyncPlan plan = CollectionSyncPlan.of(
                    List.of(
                            new UpsertVector(
                                    "doc-new",
                                    new float[]{0.3f, 0.4f, 0.5f},
                                    Map.of("path", "guide/new.md", "title", "New doc")
                            )
                    ),
                    List.of(
                            new PartialUpdateVector(
                                    "doc-1",
                                    Map.of("title", "Intro v3", "obsolete", null)
                            )
                    ),
                    List.of("doc-removed")
            ).withUpsertMode(BulkIngestMode.BINARY)
                    .withUpsertBatchSize(512)
                    .withFlushAfterSync(true);

            client.collection("markdown-kb").synchronize(plan);
        }
    }
}
