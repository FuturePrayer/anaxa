package cn.suhoan.anaxa.server;

import cn.suhoan.anaxa.common.json.JsonSupport;
import cn.suhoan.anaxa.common.model.CollectionStats;
import cn.suhoan.anaxa.common.model.SearchResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class AnaxaHttpServerTest {
    @TempDir
    Path tempDir;

    @Test
    void servesCollectionAndSearchEndpoints() throws Exception {
        try (AnaxaHttpServer server = new AnaxaHttpServer(new ServerConfig("127.0.0.1", 0, tempDir, 96L))) {
            server.start();
            HttpClient client = HttpClient.newHttpClient();
            String baseUrl = "http://127.0.0.1:" + server.port();

            HttpResponse<String> createCollection = sendJson(
                    client,
                    baseUrl + "/collections",
                    "POST",
                    Map.of("name", "docs", "dimension", 3, "metric", "COSINE", "flushThresholdBytes", 96)
            );
            assertEquals(201, createCollection.statusCode());
            CollectionStats created = JsonSupport.mapper().readValue(createCollection.body(), CollectionStats.class);
            assertEquals("docs", created.name());
            assertNotNull(createCollection.headers().firstValue("X-Trace-Id").orElse(null));

            HttpResponse<String> upsert = sendJson(
                    client,
                    baseUrl + "/collections/docs/vectors",
                    "POST",
                    Map.of("vectors", List.of(
                            Map.of("id", "alpha", "vector", List.of(1.0F, 0.0F, 0.0F), "payload", Map.of("tenant", "blue")),
                            Map.of("id", "beta", "vector", List.of(0.0F, 1.0F, 0.0F), "payload", Map.of("tenant", "red"))
                    ))
            );
            assertEquals(200, upsert.statusCode());

            HttpResponse<String> search = sendJson(
                    client,
                    baseUrl + "/collections/docs/search",
                    "POST",
                    Map.of("vector", List.of(1.0F, 0.0F, 0.0F), "topK", 2, "filter", Map.of("tenant", "blue"))
            );
            assertEquals(200, search.statusCode());

            SearchResponse response = JsonSupport.mapper().readValue(search.body(), SearchResponse.class);
            assertFalse(response.hits().isEmpty());
            assertEquals("alpha", response.hits().get(0).id());
            assertNotNull(search.headers().firstValue("X-Trace-Id").orElse(null));
        }
    }

    @Test
    void servesManualFlushEndpoint() throws Exception {
        try (AnaxaHttpServer server = new AnaxaHttpServer(new ServerConfig("127.0.0.1", 0, tempDir.resolve("http-flush"), 1_000_000L))) {
            server.start();
            HttpClient client = HttpClient.newHttpClient();
            String baseUrl = "http://127.0.0.1:" + server.port();

            assertEquals(201, sendJson(
                    client,
                    baseUrl + "/collections",
                    "POST",
                    Map.of("name", "docs", "dimension", 3, "metric", "COSINE", "flushThresholdBytes", 1_000_000)
            ).statusCode());

            assertEquals(200, sendJson(
                    client,
                    baseUrl + "/collections/docs/vectors",
                    "POST",
                    Map.of("vectors", List.of(
                            Map.of("id", "alpha", "vector", List.of(1.0F, 0.0F, 0.0F), "payload", Map.of("tenant", "blue"))
                    ))
            ).statusCode());

            CollectionStats beforeFlush = collectionStats(client, baseUrl, "docs", null, null);
            assertEquals(0, beforeFlush.segmentCount());

            HttpResponse<String> flush = sendJson(
                    client,
                    baseUrl + "/collections/docs/flush",
                    "POST",
                    Map.of()
            );
            assertEquals(200, flush.statusCode());
            CollectionStats flushed = JsonSupport.mapper().readValue(flush.body(), CollectionStats.class);
            assertEquals(1, flushed.segmentCount());
            assertFalse(flushed.flushInProgress());

            SearchResponse response = JsonSupport.mapper().readValue(sendJson(
                    client,
                    baseUrl + "/collections/docs/search",
                    "POST",
                    Map.of("vector", List.of(1.0F, 0.0F, 0.0F), "topK", 10, "filter", Map.of())
            ).body(), SearchResponse.class);
            assertEquals(List.of("alpha"), response.hits().stream().map(hit -> hit.id()).toList());
        }
    }

    @Test
    void supportsNdjsonBulkIngestAndPartialPayloadUpdates() throws Exception {
        try (AnaxaHttpServer server = new AnaxaHttpServer(new ServerConfig("127.0.0.1", 0, tempDir.resolve("http-ndjson"), 1_000_000L))) {
            server.start();
            HttpClient client = HttpClient.newHttpClient();
            String baseUrl = "http://127.0.0.1:" + server.port();

            assertEquals(201, sendJson(
                    client,
                    baseUrl + "/collections",
                    "POST",
                    Map.of("name", "docs", "dimension", 3, "metric", "COSINE", "flushThresholdBytes", 1_000_000)
            ).statusCode());

            String ndjson = """
                    {"id":"alpha","vector":[1.0,0.0,0.0],"payload":{"tenant":"blue","meta":{"section":"draft"}}}
                    {"id":"beta","vector":[0.0,1.0,0.0],"payload":{"tenant":"red"}}
                    """;
            HttpResponse<String> ndjsonUpsert = sendBody(
                    client,
                    baseUrl + "/collections/docs/vectors",
                    "POST",
                    ndjson,
                    "application/x-ndjson",
                    null,
                    null
            );
            assertEquals(200, ndjsonUpsert.statusCode());
            assertEquals(2L, JsonSupport.mapper().readValue(ndjsonUpsert.body(), CollectionStats.class).liveVectorCount());

            HttpResponse<String> patch = sendJson(
                    client,
                    baseUrl + "/collections/docs/vectors",
                    "PATCH",
                    Map.of("updates", List.of(
                            Map.of(
                                    "id", "alpha",
                                    "payload", Map.of(
                                            "tenant", "green",
                                            "meta", Map.of("section", "intro")
                                    )
                            )
                    ))
            );
            assertEquals(200, patch.statusCode());

            SearchResponse response = JsonSupport.mapper().readValue(sendJson(
                    client,
                    baseUrl + "/collections/docs/search",
                    "POST",
                    Map.of("vector", List.of(1.0F, 0.0F, 0.0F), "topK", 10, "filter", Map.of("tenant", "green"))
            ).body(), SearchResponse.class);
            assertEquals(List.of("alpha"), response.hits().stream().map(hit -> hit.id()).toList());
            assertEquals(Map.of("section", "intro"), response.hits().get(0).payload().get("meta"));
        }
    }

    @Test
    void servesDeleteAndCompactionEndpoints() throws Exception {
        try (AnaxaHttpServer server = new AnaxaHttpServer(new ServerConfig("127.0.0.1", 0, tempDir.resolve("http-delete"), 1L))) {
            server.start();
            HttpClient client = HttpClient.newHttpClient();
            String baseUrl = "http://127.0.0.1:" + server.port();

            HttpResponse<String> createCollection = sendJson(
                    client,
                    baseUrl + "/collections",
                    "POST",
                    Map.of("name", "docs", "dimension", 3, "metric", "COSINE", "flushThresholdBytes", 1)
            );
            assertEquals(201, createCollection.statusCode());

            HttpResponse<String> upsert = sendJson(
                    client,
                    baseUrl + "/collections/docs/vectors",
                    "POST",
                    Map.of("vectors", List.of(
                            Map.of("id", "alpha", "vector", List.of(1.0F, 0.0F, 0.0F), "payload", Map.of("tenant", "blue"))
                    ))
            );
            assertEquals(200, upsert.statusCode());

            waitForCollectionStats(client, baseUrl, stats -> stats.segmentCount() == 1, "live segment flush did not complete");

            HttpResponse<String> delete = sendJson(
                    client,
                    baseUrl + "/collections/docs/deletions",
                    "POST",
                    Map.of("ids", List.of("alpha"))
            );
            assertEquals(200, delete.statusCode());

            waitForCollectionStats(client, baseUrl, stats ->
                            stats.segmentCount() == 2 || (stats.segmentCount() == 0 && stats.tombstoneCount() == 0L),
                    "tombstone flush or auto compaction did not complete");

            HttpResponse<String> compact = sendJson(
                    client,
                    baseUrl + "/collections/docs/compact",
                    "POST",
                    Map.of()
            );
            assertEquals(200, compact.statusCode());
            CollectionStats compacted = JsonSupport.mapper().readValue(compact.body(), CollectionStats.class);
            assertEquals(0, compacted.segmentCount());
            assertEquals(0L, compacted.liveVectorCount());
            assertEquals(0L, compacted.tombstoneCount());

            HttpResponse<String> search = sendJson(
                    client,
                    baseUrl + "/collections/docs/search",
                    "POST",
                    Map.of("vector", List.of(1.0F, 0.0F, 0.0F), "topK", 10, "filter", Map.of())
            );
            assertEquals(200, search.statusCode());
            SearchResponse response = JsonSupport.mapper().readValue(search.body(), SearchResponse.class);
            assertTrue(response.hits().isEmpty());
        }
    }

    @Test
    void enforcesApiKeysAndExposesMetrics() throws Exception {
        try (AnaxaHttpServer server = new AnaxaHttpServer(new ServerConfig(
                "127.0.0.1",
                0,
                tempDir.resolve("http-auth"),
                96L,
                Set.of("secret"),
                10_000,
                100
        ))) {
            server.start();
            HttpClient client = HttpClient.newHttpClient();
            String baseUrl = "http://127.0.0.1:" + server.port();

            HttpRequest unauthorized = HttpRequest.newBuilder(URI.create(baseUrl + "/collections"))
                    .GET()
                    .build();
            HttpResponse<String> unauthorizedResponse = client.send(unauthorized, HttpResponse.BodyHandlers.ofString());
            assertEquals(401, unauthorizedResponse.statusCode());
            assertEquals("ApiKey", unauthorizedResponse.headers().firstValue("WWW-Authenticate").orElse(null));

            HttpResponse<String> createCollection = sendJson(
                    client,
                    baseUrl + "/collections",
                    "POST",
                    Map.of("name", "docs", "dimension", 3, "metric", "COSINE", "flushThresholdBytes", 96),
                    "secret"
            );
            assertEquals(201, createCollection.statusCode());

            HttpRequest metricsRequest = HttpRequest.newBuilder(URI.create(baseUrl + "/metrics"))
                    .header("X-API-Key", "secret")
                    .GET()
                    .build();
            HttpResponse<String> metrics = client.send(metricsRequest, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, metrics.statusCode());
            assertTrue(metrics.body().contains("anaxa_http_requests_total"));
            assertTrue(metrics.body().contains("anaxa_engine_collections 1"));
        }
    }

    @Test
    void rateLimitsProtectedRequests() throws Exception {
        try (AnaxaHttpServer server = new AnaxaHttpServer(new ServerConfig(
                "127.0.0.1",
                0,
                tempDir.resolve("http-rate-limit"),
                96L,
                Set.of("secret"),
                1,
                1
        ))) {
            server.start();
            HttpClient client = HttpClient.newHttpClient();
            String baseUrl = "http://127.0.0.1:" + server.port();

            HttpResponse<String> first = sendJson(
                    client,
                    baseUrl + "/collections",
                    "POST",
                    Map.of("name", "docs", "dimension", 3, "metric", "COSINE", "flushThresholdBytes", 96),
                    "secret"
            );
            assertEquals(201, first.statusCode());

            HttpRequest secondRequest = HttpRequest.newBuilder(URI.create(baseUrl + "/collections"))
                    .header("X-API-Key", "secret")
                    .header("X-Tenant-Id", "default")
                    .GET()
                    .build();
            HttpResponse<String> second = client.send(secondRequest, HttpResponse.BodyHandlers.ofString());
            assertEquals(429, second.statusCode());
            assertEquals("1", second.headers().firstValue("Retry-After").orElse(null));
        }
    }

    @Test
    void isolatesCollectionsAcrossTenantsAndScopesRbac() throws Exception {
        Path dataDir = tempDir.resolve("http-tenants");
        Path apiKeyFile = tempDir.resolve("tenant-api-keys.json");
        Files.writeString(apiKeyFile, JsonSupport.writeString(Map.of(
                "keys", List.of(
                        Map.of("id", "reader-a", "secret", "reader-a-key", "roles", List.of("READER"), "tenant", "tenant-a"),
                        Map.of("id", "writer-a", "secret", "writer-a-key", "roles", List.of("WRITER"), "tenant", "tenant-a"),
                        Map.of("id", "reader-b", "secret", "reader-b-key", "roles", List.of("READER"), "tenant", "tenant-b"),
                        Map.of("id", "writer-b", "secret", "writer-b-key", "roles", List.of("WRITER"), "tenant", "tenant-b"),
                        Map.of("id", "admin", "secret", "admin-key", "roles", List.of("ADMIN"), "globalTenantAccess", true)
                )
        )));

        try (AnaxaHttpServer server = new AnaxaHttpServer(new ServerConfig(
                "127.0.0.1",
                0,
                dataDir,
                1_000_000L,
                Set.of(),
                apiKeyFile,
                10_000,
                100,
                0L,
                dataDir.resolve("audit").resolve("audit.log"),
                dataDir.resolve("backups")
        ))) {
            server.start();
            HttpClient client = HttpClient.newHttpClient();
            String baseUrl = "http://127.0.0.1:" + server.port();

            assertEquals(201, sendJson(
                    client,
                    baseUrl + "/collections",
                    "POST",
                    Map.of("name", "docs", "dimension", 3, "metric", "COSINE", "flushThresholdBytes", 1_000_000),
                    "writer-a-key"
            ).statusCode());
            assertEquals(201, sendJson(
                    client,
                    baseUrl + "/collections",
                    "POST",
                    Map.of("name", "docs", "dimension", 3, "metric", "COSINE", "flushThresholdBytes", 1_000_000),
                    "writer-b-key"
            ).statusCode());

            assertEquals(200, sendJson(
                    client,
                    baseUrl + "/collections/docs/vectors",
                    "POST",
                    Map.of("vectors", List.of(
                            Map.of("id", "alpha", "vector", List.of(1.0F, 0.0F, 0.0F), "payload", Map.of("tenant", "a"))
                    )),
                    "writer-a-key"
            ).statusCode());
            assertEquals(200, sendJson(
                    client,
                    baseUrl + "/collections/docs/vectors",
                    "POST",
                    Map.of("vectors", List.of(
                            Map.of("id", "beta", "vector", List.of(0.0F, 1.0F, 0.0F), "payload", Map.of("tenant", "b"))
                    )),
                    "writer-b-key"
            ).statusCode());

            SearchResponse tenantASearch = JsonSupport.mapper().readValue(sendJson(
                    client,
                    baseUrl + "/collections/docs/search",
                    "POST",
                    Map.of("vector", List.of(1.0F, 0.0F, 0.0F), "topK", 10, "filter", Map.of()),
                    "reader-a-key"
            ).body(), SearchResponse.class);
            assertEquals(List.of("alpha"), tenantASearch.hits().stream().map(hit -> hit.id()).toList());

            SearchResponse tenantBSearch = JsonSupport.mapper().readValue(sendJson(
                    client,
                    baseUrl + "/collections/docs/search",
                    "POST",
                    Map.of("vector", List.of(0.0F, 1.0F, 0.0F), "topK", 10, "filter", Map.of()),
                    "reader-b-key"
            ).body(), SearchResponse.class);
            assertEquals(List.of("beta"), tenantBSearch.hits().stream().map(hit -> hit.id()).toList());

            HttpRequest forbiddenCrossTenant = HttpRequest.newBuilder(URI.create(baseUrl + "/collections/docs"))
                    .header("X-API-Key", "reader-a-key")
                    .header("X-Tenant-Id", "tenant-b")
                    .GET()
                    .build();
            assertEquals(403, client.send(forbiddenCrossTenant, HttpResponse.BodyHandlers.ofString()).statusCode());

            HttpRequest adminList = HttpRequest.newBuilder(URI.create(baseUrl + "/collections"))
                    .header("X-API-Key", "admin-key")
                    .GET()
                    .build();
            HttpResponse<String> adminListResponse = client.send(adminList, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, adminListResponse.statusCode());
            List<CollectionStats> allCollections = JsonSupport.mapper()
                    .readerForListOf(CollectionStats.class)
                    .readValue(adminListResponse.body());
            assertEquals(List.of("tenant-a", "tenant-b"), allCollections.stream().map(CollectionStats::tenantId).sorted().toList());

            HttpRequest tenantScopedList = HttpRequest.newBuilder(URI.create(baseUrl + "/collections"))
                    .header("X-API-Key", "admin-key")
                    .header("X-Tenant-Id", "tenant-b")
                    .GET()
                    .build();
            HttpResponse<String> tenantScopedResponse = client.send(tenantScopedList, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, tenantScopedResponse.statusCode());
            List<CollectionStats> tenantBCollections = JsonSupport.mapper()
                    .readerForListOf(CollectionStats.class)
                    .readValue(tenantScopedResponse.body());
            assertEquals(List.of("tenant-b"), tenantBCollections.stream().map(CollectionStats::tenantId).distinct().toList());
        }
    }

    @Test
    void enforcesTenantQuotasAndExportsInternalStageMetrics() throws Exception {
        Path dataDir = tempDir.resolve("http-tenant-quotas");
        Path apiKeyFile = tempDir.resolve("tenant-quotas.json");
        Files.writeString(apiKeyFile, JsonSupport.writeString(Map.of(
                "keys", List.of(
                        Map.of("id", "writer-a", "secret", "writer-a-key", "roles", List.of("WRITER"), "tenant", "tenant-a"),
                        Map.of("id", "writer-b", "secret", "writer-b-key", "roles", List.of("WRITER"), "tenant", "tenant-b"),
                        Map.of("id", "admin", "secret", "admin-key", "roles", List.of("ADMIN"), "globalTenantAccess", true)
                ),
                "tenants", List.of(
                        Map.of("id", "tenant-a", "maxCollections", 1, "maxLiveVectors", 2, "rateLimitPerMinute", 10_000, "rateLimitBurst", 100),
                        Map.of("id", "tenant-b", "maxCollections", 2, "maxStorageBytes", 1, "rateLimitPerMinute", 10_000, "rateLimitBurst", 100)
                )
        )));

        try (AnaxaHttpServer server = new AnaxaHttpServer(new ServerConfig(
                "127.0.0.1",
                0,
                dataDir,
                1L,
                Set.of(),
                apiKeyFile,
                10_000,
                100,
                0L,
                dataDir.resolve("audit").resolve("audit.log"),
                dataDir.resolve("backups")
        ))) {
            server.start();
            HttpClient client = HttpClient.newHttpClient();
            String baseUrl = "http://127.0.0.1:" + server.port();

            assertEquals(201, sendJson(
                    client,
                    baseUrl + "/collections",
                    "POST",
                    Map.of("name", "docs", "dimension", 3, "metric", "COSINE", "flushThresholdBytes", 1),
                    "writer-a-key"
            ).statusCode());

            HttpResponse<String> secondCollection = sendJson(
                    client,
                    baseUrl + "/collections",
                    "POST",
                    Map.of("name", "docs-2", "dimension", 3, "metric", "COSINE", "flushThresholdBytes", 1),
                    "writer-a-key"
            );
            assertEquals(409, secondCollection.statusCode());

            assertEquals(200, sendJson(
                    client,
                    baseUrl + "/collections/docs/vectors",
                    "POST",
                    Map.of("vectors", List.of(
                            Map.of("id", "alpha", "vector", List.of(1.0F, 0.0F, 0.0F), "payload", Map.of("bucket", 1)),
                            Map.of("id", "beta", "vector", List.of(0.0F, 1.0F, 0.0F), "payload", Map.of("bucket", 2))
                    )),
                    "writer-a-key"
            ).statusCode());

            waitForCollectionStats(
                    client,
                    baseUrl,
                    "docs",
                    "writer-a-key",
                    null,
                    stats -> stats.segmentCount() >= 1,
                    "tenant-a segment flush did not complete"
            );

            HttpResponse<String> vectorQuotaExceeded = sendJson(
                    client,
                    baseUrl + "/collections/docs/vectors",
                    "POST",
                    Map.of("vectors", List.of(
                            Map.of("id", "gamma", "vector", List.of(0.0F, 0.0F, 1.0F), "payload", Map.of("bucket", 3))
                    )),
                    "writer-a-key"
            );
            assertEquals(409, vectorQuotaExceeded.statusCode());

            assertEquals(201, sendJson(
                    client,
                    baseUrl + "/collections",
                    "POST",
                    Map.of("name", "tiny", "dimension", 3, "metric", "COSINE", "flushThresholdBytes", 1),
                    "writer-b-key"
            ).statusCode());

            HttpResponse<String> storageQuotaExceeded = sendJson(
                    client,
                    baseUrl + "/collections/tiny/vectors",
                    "POST",
                    Map.of("vectors", List.of(
                            Map.of("id", "tiny-1", "vector", List.of(1.0F, 0.0F, 0.0F), "payload", Map.of("size", "too-big"))
                    )),
                    "writer-b-key"
            );
            assertEquals(409, storageQuotaExceeded.statusCode());

            SearchResponse firstSearch = JsonSupport.mapper().readValue(sendJson(
                    client,
                    baseUrl + "/collections/docs/search",
                    "POST",
                    Map.of("vector", List.of(1.0F, 0.0F, 0.0F), "topK", 1, "filter", Map.of()),
                    "admin-key",
                    "tenant-a"
            ).body(), SearchResponse.class);
            assertEquals(List.of("alpha"), firstSearch.hits().stream().map(hit -> hit.id()).toList());

            sendJson(
                    client,
                    baseUrl + "/collections/docs/search",
                    "POST",
                    Map.of("vector", List.of(1.0F, 0.0F, 0.0F), "topK", 2, "filter", Map.of()),
                    "admin-key",
                    "tenant-a"
            );
            sendJson(
                    client,
                    baseUrl + "/collections/docs/search",
                    "POST",
                    Map.of("vector", List.of(1.0F, 0.0F, 0.0F), "topK", 2, "filter", Map.of()),
                    "admin-key",
                    "tenant-a"
            );

            assertEquals(200, sendJson(
                    client,
                    baseUrl + "/collections/docs/deletions",
                    "POST",
                    Map.of("ids", List.of("beta")),
                    "admin-key",
                    "tenant-a"
            ).statusCode());
            waitForCollectionStats(
                    client,
                    baseUrl,
                    "docs",
                    "admin-key",
                    "tenant-a",
                    stats -> stats.segmentCount() >= 2 || (stats.segmentCount() >= 1 && stats.tombstoneCount() == 0L),
                    "tombstone flush or auto compaction did not complete"
            );

            assertEquals(200, sendJson(
                    client,
                    baseUrl + "/collections/docs/compact",
                    "POST",
                    Map.of(),
                    "admin-key",
                    "tenant-a"
            ).statusCode());

            HttpRequest metricsRequest = HttpRequest.newBuilder(URI.create(baseUrl + "/metrics"))
                    .header("X-API-Key", "admin-key")
                    .header("X-Tenant-Id", "tenant-a")
                    .GET()
                    .build();
            HttpResponse<String> metrics = client.send(metricsRequest, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, metrics.statusCode());
            assertTrue(metrics.body().contains("anaxa_engine_flush_total{collection=\"docs\",tenant=\"tenant-a\"}"));
            assertTrue(metrics.body().contains("anaxa_engine_compaction_total{collection=\"docs\",tenant=\"tenant-a\"}"));
            assertTrue(metrics.body().contains("anaxa_search_query_cache_hits_total{collection=\"docs\",tenant=\"tenant-a\"} 1"));
            assertTrue(metrics.body().contains("anaxa_search_source_index_cache_hits_total{collection=\"docs\",tenant=\"tenant-a\"}"));
        }
    }

    @Test
    void reloadsApiKeysEnforcesRbacAndSupportsBackupRestore() throws Exception {
        Path dataDir = tempDir.resolve("http-security");
        Path apiKeyFile = tempDir.resolve("api-keys.json");
        Path auditLog = tempDir.resolve("audit").resolve("audit.log");
        Path backupDir = tempDir.resolve("backups");
        Files.createDirectories(apiKeyFile.getParent());
        Files.writeString(apiKeyFile, JsonSupport.writeString(Map.of(
                "keys", List.of(
                        Map.of("id", "reader", "secret", "reader-key", "roles", List.of("READER")),
                        Map.of("id", "writer", "secret", "writer-key", "roles", List.of("WRITER")),
                        Map.of("id", "admin", "secret", "admin-key", "roles", List.of("ADMIN"))
                )
        )));

        try (AnaxaHttpServer server = new AnaxaHttpServer(new ServerConfig(
                "127.0.0.1",
                0,
                dataDir,
                1_000_000L,
                Set.of(),
                apiKeyFile,
                10_000,
                100,
                0L,
                auditLog,
                backupDir
        ))) {
            server.start();
            HttpClient client = HttpClient.newHttpClient();
            String baseUrl = "http://127.0.0.1:" + server.port();

            HttpRequest listCollections = HttpRequest.newBuilder(URI.create(baseUrl + "/collections"))
                    .header("X-API-Key", "reader-key")
                    .GET()
                    .build();
            assertEquals(200, client.send(listCollections, HttpResponse.BodyHandlers.ofString()).statusCode());

            HttpResponse<String> forbiddenCreate = sendJson(
                    client,
                    baseUrl + "/collections",
                    "POST",
                    Map.of("name", "docs", "dimension", 3, "metric", "COSINE", "flushThresholdBytes", 1_000_000),
                    "reader-key"
            );
            assertEquals(403, forbiddenCreate.statusCode());

            HttpResponse<String> createCollection = sendJson(
                    client,
                    baseUrl + "/collections",
                    "POST",
                    Map.of("name", "docs", "dimension", 3, "metric", "COSINE", "flushThresholdBytes", 1_000_000),
                    "writer-key"
            );
            assertEquals(201, createCollection.statusCode());

            HttpResponse<String> upsert = sendJson(
                    client,
                    baseUrl + "/collections/docs/vectors",
                    "POST",
                    Map.of("vectors", List.of(
                            Map.of("id", "alpha", "vector", List.of(1.0F, 0.0F, 0.0F), "payload", Map.of("tenant", "blue"))
                    )),
                    "writer-key"
            );
            assertEquals(200, upsert.statusCode());

            HttpResponse<String> forbiddenFlush = sendJson(
                    client,
                    baseUrl + "/collections/docs/flush",
                    "POST",
                    Map.of(),
                    "writer-key"
            );
            assertEquals(403, forbiddenFlush.statusCode());

            HttpResponse<String> adminFlush = sendJson(
                    client,
                    baseUrl + "/collections/docs/flush",
                    "POST",
                    Map.of(),
                    "admin-key"
            );
            assertEquals(200, adminFlush.statusCode());

            HttpResponse<String> search = sendJson(
                    client,
                    baseUrl + "/collections/docs/search",
                    "POST",
                    Map.of("vector", List.of(1.0F, 0.0F, 0.0F), "topK", 10, "filter", Map.of()),
                    "reader-key"
            );
            assertEquals(200, search.statusCode());
            assertEquals("alpha", JsonSupport.mapper().readValue(search.body(), SearchResponse.class).hits().getFirst().id());

            HttpRequest writerMetrics = HttpRequest.newBuilder(URI.create(baseUrl + "/metrics"))
                    .header("X-API-Key", "writer-key")
                    .GET()
                    .build();
            assertEquals(403, client.send(writerMetrics, HttpResponse.BodyHandlers.ofString()).statusCode());

            Files.writeString(apiKeyFile, JsonSupport.writeString(Map.of(
                    "keys", List.of(
                            Map.of("id", "reader", "secret", "reader-key", "roles", List.of("READER")),
                            Map.of("id", "writer", "secret", "writer-rotated", "roles", List.of("WRITER")),
                            Map.of("id", "admin", "secret", "admin-key", "roles", List.of("ADMIN"))
                    )
            )));
            Thread.sleep(1_100L);

            HttpResponse<String> staleWriter = sendJson(
                    client,
                    baseUrl + "/collections/docs/vectors",
                    "POST",
                    Map.of("vectors", List.of(
                            Map.of("id", "beta", "vector", List.of(0.0F, 1.0F, 0.0F), "payload", Map.of("tenant", "red"))
                    )),
                    "writer-key"
            );
            assertEquals(401, staleWriter.statusCode());

            HttpResponse<String> rotatedWriter = sendJson(
                    client,
                    baseUrl + "/collections/docs/vectors",
                    "POST",
                    Map.of("vectors", List.of(
                            Map.of("id", "beta", "vector", List.of(0.0F, 1.0F, 0.0F), "payload", Map.of("tenant", "red"))
                    )),
                    "writer-rotated"
            );
            assertEquals(200, rotatedWriter.statusCode());

            HttpResponse<String> backup = sendJson(
                    client,
                    baseUrl + "/collections/docs/backup",
                    "POST",
                    Map.of("backupId", "nightly"),
                    "admin-key"
            );
            assertEquals(200, backup.statusCode());

            HttpResponse<String> restore = sendJson(
                    client,
                    baseUrl + "/backups/nightly/restore",
                    "POST",
                    Map.of("sourceCollection", "docs", "collectionName", "docs-restored"),
                    "admin-key"
            );
            assertEquals(201, restore.statusCode());

            HttpResponse<String> restoredSearch = sendJson(
                    client,
                    baseUrl + "/collections/docs-restored/search",
                    "POST",
                    Map.of("vector", List.of(1.0F, 0.0F, 0.0F), "topK", 10, "filter", Map.of()),
                    "reader-key"
            );
            assertEquals(200, restoredSearch.statusCode());
            SearchResponse restored = JsonSupport.mapper().readValue(restoredSearch.body(), SearchResponse.class);
            assertEquals(List.of("alpha", "beta"), restored.hits().stream().map(hit -> hit.id()).toList());

            HttpRequest adminMetrics = HttpRequest.newBuilder(URI.create(baseUrl + "/metrics"))
                    .header("X-API-Key", "admin-key")
                    .GET()
                    .build();
            HttpResponse<String> metrics = client.send(adminMetrics, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, metrics.statusCode());
            assertTrue(metrics.body().contains("anaxa_http_authorization_denied_total"));
            assertTrue(metrics.body().contains("anaxa_search_queries_total"));
        }

        String audit = Files.readString(auditLog);
        assertTrue(audit.contains("/collections/{name}/backup"));
        assertTrue(audit.contains("/backups/{id}/restore"));
    }

    private HttpResponse<String> sendJson(HttpClient client, String uri, String method, Object body) throws Exception {
        return sendJson(client, uri, method, body, null);
    }

    private HttpResponse<String> sendJson(HttpClient client, String uri, String method, Object body, String apiKey) throws Exception {
        return sendJson(client, uri, method, body, apiKey, null);
    }

    private HttpResponse<String> sendJson(
            HttpClient client,
            String uri,
            String method,
            Object body,
            String apiKey,
            String tenantId
    ) throws Exception {
        return sendBody(client, uri, method, JsonSupport.writeString(body), "application/json", apiKey, tenantId);
    }

    private HttpResponse<String> sendBody(
            HttpClient client,
            String uri,
            String method,
            String body,
            String contentType,
            String apiKey,
            String tenantId
    ) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(uri))
                .header("Content-Type", contentType);
        if (apiKey != null) {
            builder.header("X-API-Key", apiKey);
        }
        if (tenantId != null) {
            builder.header("X-Tenant-Id", tenantId);
        }
        builder.method(method, HttpRequest.BodyPublishers.ofString(body));
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private CollectionStats collectionStats(HttpClient client, String baseUrl) throws Exception {
        return collectionStats(client, baseUrl, "docs", null, null);
    }

    private CollectionStats collectionStats(
            HttpClient client,
            String baseUrl,
            String collectionName,
            String apiKey,
            String tenantId
    ) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + "/collections/" + collectionName));
        if (apiKey != null) {
            builder.header("X-API-Key", apiKey);
        }
        if (tenantId != null) {
            builder.header("X-Tenant-Id", tenantId);
        }
        HttpRequest request = builder
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        return JsonSupport.mapper().readValue(response.body(), CollectionStats.class);
    }

    private void waitForCollectionStats(
            HttpClient client,
            String baseUrl,
            java.util.function.Predicate<CollectionStats> predicate,
            String message
    ) throws Exception {
        waitForCollectionStats(client, baseUrl, "docs", null, null, predicate, message);
    }

    private void waitForCollectionStats(
            HttpClient client,
            String baseUrl,
            String collectionName,
            String apiKey,
            String tenantId,
            java.util.function.Predicate<CollectionStats> predicate,
            String message
    ) throws Exception {
        waitFor(() -> {
            try {
                return predicate.test(collectionStats(client, baseUrl, collectionName, apiKey, tenantId));
            } catch (Exception exception) {
                throw new RuntimeException(exception);
            }
        }, message);
    }

    private void waitFor(BooleanSupplier supplier, String message) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(5));
        while (Instant.now().isBefore(deadline)) {
            if (supplier.getAsBoolean()) {
                return;
            }
            Thread.sleep(25L);
        }
        fail(message);
    }
}
