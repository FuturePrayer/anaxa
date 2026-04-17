package cn.suhoan.anaxa.sdk;

import cn.suhoan.anaxa.common.json.JsonSupport;
import cn.suhoan.anaxa.common.model.CollectionStats;
import cn.suhoan.anaxa.common.model.ErrorResponse;
import cn.suhoan.anaxa.common.model.MetricType;
import cn.suhoan.anaxa.common.model.PartialUpdateVector;
import cn.suhoan.anaxa.common.model.SearchHit;
import cn.suhoan.anaxa.common.model.SearchResponse;
import cn.suhoan.anaxa.common.model.UpsertVector;
import cn.suhoan.anaxa.common.util.BinaryVectorStreams;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnaxaClientTest {
    private HttpServer server;
    private ExecutorService executor;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    @Test
    void searchSendsTenantAndApiKeyHeaders() throws Exception {
        RecordedRequest[] captured = new RecordedRequest[1];
        startServer(exchange -> {
            captured[0] = record(exchange);
            writeJson(
                    exchange,
                    200,
                    new SearchResponse(List.of(new SearchHit("doc-1", 0.98f, Map.of("tenant", "team-a"), 1L)))
            );
        }, "/collections/docs/search");

        try (AnaxaClient client = AnaxaClient.builder()
                .baseUri(baseUri())
                .apiKey("reader-secret")
                .defaultTenantId("team-a")
                .build()) {
            SearchResponse response = client.collection("docs").search(
                    new float[]{1.0f, 0.0f, 0.0f},
                    3,
                    PayloadFilterBuilder.filter().eq("tenant", "team-a")
            );
            assertEquals(1, response.hits().size());
        }

        assertEquals("POST", captured[0].method());
        assertEquals("reader-secret", captured[0].header("X-API-Key"));
        assertEquals("team-a", captured[0].header("X-Tenant-Id"));
        assertTrue(captured[0].bodyAsString().contains("\"topK\":3"));
        assertTrue(captured[0].bodyAsString().contains("\"tenant\":\"team-a\""));
    }

    @Test
    void bulkUpsertUsesBinaryBatchesAndFlushesWhenRequested() throws Exception {
        List<RecordedRequest> vectorRequests = new CopyOnWriteArrayList<>();
        AtomicInteger flushCalls = new AtomicInteger();

        startServer(
                Map.of(
                        "/collections/docs", exchange -> writeJson(exchange, 200, stats("docs", 3)),
                        "/collections/docs/vectors", exchange -> {
                            vectorRequests.add(record(exchange));
                            writeJson(exchange, 200, stats("docs", 3));
                        },
                        "/collections/docs/flush", exchange -> {
                            flushCalls.incrementAndGet();
                            writeJson(exchange, 200, stats("docs", 3));
                        }
                )
        );

        try (AnaxaClient client = AnaxaClient.builder().baseUri(baseUri()).build()) {
            BulkIngestResult result = client.collection("docs").bulkUpsert(
                    List.of(
                            new UpsertVector("a", new float[]{1.0f, 0.0f, 0.0f}, Map.of()),
                            new UpsertVector("b", new float[]{0.0f, 1.0f, 0.0f}, Map.of()),
                            new UpsertVector("c", new float[]{0.0f, 0.0f, 1.0f}, Map.of())
                    ),
                    BulkIngestOptions.defaults()
                            .withBatchSize(2)
                            .withMode(BulkIngestMode.BINARY)
                            .withFlushAfterWrite(true)
            );

            assertEquals(3L, result.vectorsIngested());
            assertEquals(2, result.batchesSent());
            assertTrue(result.flushed());
        }

        assertEquals(2, vectorRequests.size());
        assertEquals("application/vnd.anaxa.vector-batch; charset=utf-8", vectorRequests.get(0).header("Content-Type"));
        assertEquals(2L, decodeBinaryCount(vectorRequests.get(0).body(), 3));
        assertEquals(1L, decodeBinaryCount(vectorRequests.get(1).body(), 3));
        assertEquals(1, flushCalls.get());
    }

    @Test
    void synchronizeAppliesMutationsInExpectedOrder() throws Exception {
        List<String> sequence = new CopyOnWriteArrayList<>();

        startServer(
                Map.of(
                        "/collections/docs/vectors", exchange -> {
                            sequence.add("upsert-" + exchange.getRequestMethod());
                            writeJson(exchange, 200, stats("docs", 3));
                        },
                        "/collections/docs/deletions", exchange -> {
                            sequence.add("delete");
                            writeJson(exchange, 200, stats("docs", 3));
                        },
                        "/collections/docs/flush", exchange -> {
                            sequence.add("flush");
                            writeJson(exchange, 200, stats("docs", 3));
                        },
                        "/collections/docs/compact", exchange -> {
                            sequence.add("compact");
                            writeJson(exchange, 200, stats("docs", 3));
                        }
                )
        );

        try (AnaxaClient client = AnaxaClient.builder().baseUri(baseUri()).build()) {
            CollectionSyncResult result = client.collection("docs").synchronize(
                    CollectionSyncPlan.of(
                                    List.of(
                                            new UpsertVector("a", new float[]{1.0f, 0.0f, 0.0f}, Map.of()),
                                            new UpsertVector("b", new float[]{0.0f, 1.0f, 0.0f}, Map.of())
                                    ),
                                    List.of(new PartialUpdateVector("a", Map.of("title", "v2"))),
                                    List.of("b")
                            )
                            .withUpsertMode(BulkIngestMode.JSON)
                            .withUpsertBatchSize(1)
                            .withPartialUpdateBatchSize(1)
                            .withDeletionBatchSize(1)
                            .withFlushAfterSync(true)
                            .withCompactAfterSync(true)
            );

            assertEquals(2L, result.upsertsApplied());
            assertEquals(1L, result.partialUpdatesApplied());
            assertEquals(1L, result.deletionsApplied());
            assertTrue(result.flushed());
            assertTrue(result.compacted());
        }

        assertEquals(
                List.of("upsert-POST", "upsert-POST", "upsert-PATCH", "delete", "flush", "compact"),
                sequence
        );
    }

    @Test
    void exposesRichServerErrorContext() throws Exception {
        startServer(exchange -> writeJson(exchange, 404, new ErrorResponse("Collection not found: missing", "trace-123")), "/collections/missing");

        try (AnaxaClient client = AnaxaClient.builder().baseUri(baseUri()).build()) {
            AnaxaClientException exception = assertThrows(
                    AnaxaClientException.class,
                    () -> client.getCollection("missing")
            );
            assertEquals(404, exception.statusCode());
            assertEquals("test-trace", exception.traceId());
            assertTrue(exception.responseBody().contains("Collection not found: missing"));
        }
    }

    private void startServer(HttpHandler handler, String path) throws IOException {
        startServer(Map.of(path, handler));
    }

    private void startServer(Map<String, HttpHandler> handlers) throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        handlers.forEach(server::createContext);
        server.start();
    }

    private URI baseUri() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    private static CollectionStats stats(String name, int dimension) {
        return new CollectionStats(name, dimension, MetricType.COSINE, 10L, 0L, 1, false, false, "default", 1024L);
    }

    private static long decodeBinaryCount(byte[] body, int dimension) {
        AtomicInteger count = new AtomicInteger();
        BinaryVectorStreams.readBatch(new ByteArrayInputStream(body), dimension, ignored -> count.incrementAndGet());
        return count.get();
    }

    private static RecordedRequest record(HttpExchange exchange) throws IOException {
        byte[] body = readAllBytes(exchange.getRequestBody());
        return new RecordedRequest(exchange.getRequestMethod(), exchange.getRequestURI().getPath(), exchange.getRequestHeaders(), body);
    }

    private static void writeJson(HttpExchange exchange, int statusCode, Object body) throws IOException {
        byte[] bytes = JsonSupport.writeBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("X-Trace-Id", "test-trace");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static byte[] readAllBytes(InputStream input) throws IOException {
        return input.readAllBytes();
    }

    private record RecordedRequest(String method, String path, com.sun.net.httpserver.Headers headers, byte[] body) {
        private String header(String name) {
            return headers.getFirst(name);
        }

        private String bodyAsString() {
            return new String(body, StandardCharsets.UTF_8);
        }
    }
}
