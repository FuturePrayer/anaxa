package cn.suhoan.anaxa.engine;

import cn.suhoan.anaxa.common.model.CollectionStats;
import cn.suhoan.anaxa.common.model.CreateCollectionRequest;
import cn.suhoan.anaxa.common.model.DeleteVectorsRequest;
import cn.suhoan.anaxa.common.model.MetricType;
import cn.suhoan.anaxa.common.model.PartialUpdateVector;
import cn.suhoan.anaxa.common.model.PartialUpdateVectorsRequest;
import cn.suhoan.anaxa.common.model.SearchResponse;
import cn.suhoan.anaxa.common.model.SearchRequest;
import cn.suhoan.anaxa.common.model.UpsertVector;
import cn.suhoan.anaxa.common.model.UpsertVectorsRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class VectorDatabaseEngineTest {
    @TempDir
    Path tempDir;

    @Test
    void persistsSegmentsAndSearchesAcrossRestart() throws Exception {
        try (VectorDatabaseEngine engine = new VectorDatabaseEngine(tempDir, 96L)) {
            engine.createCollection(new CreateCollectionRequest("docs", 3, MetricType.COSINE, 96L));
            engine.upsert("docs", new UpsertVectorsRequest(List.of(
                    new UpsertVector("alpha", new float[]{1.0F, 0.0F, 0.0F}, Map.of("tenant", "blue")),
                    new UpsertVector("beta", new float[]{0.0F, 1.0F, 0.0F}, Map.of("tenant", "red")),
                    new UpsertVector("gamma", new float[]{0.9F, 0.1F, 0.0F}, Map.of("tenant", "blue"))
            )));

            waitFor(() -> engine.stats("docs").segmentCount() > 0, "segment flush did not complete");

            SearchResponse initial = engine.search("docs", new SearchRequest(
                    new float[]{1.0F, 0.0F, 0.0F},
                    2,
                    Map.of("tenant", "blue")
            ));
            assertEquals(2, initial.hits().size());
            assertEquals("alpha", initial.hits().get(0).id());
            assertTrue(initial.hits().get(0).score() >= initial.hits().get(1).score());
        }

        try (VectorDatabaseEngine reopened = new VectorDatabaseEngine(tempDir, 96L)) {
            CollectionStats stats = reopened.stats("docs");
            assertTrue(stats.segmentCount() > 0);

            SearchResponse recovered = reopened.search("docs", new SearchRequest(
                    new float[]{1.0F, 0.0F, 0.0F},
                    2,
                    Map.of("tenant", "blue")
            ));
            assertFalse(recovered.hits().isEmpty());
            assertEquals("alpha", recovered.hits().get(0).id());
        }
    }

    @Test
    void isolatesSameCollectionNameAcrossTenantsAndReloadsTenantPaths() throws Exception {
        Path dataDir = tempDir.resolve("tenant-engine");
        try (VectorDatabaseEngine engine = new VectorDatabaseEngine(dataDir, 1_000_000L)) {
            engine.createCollection("tenant-a", new CreateCollectionRequest("docs", 3, MetricType.COSINE, 1_000_000L));
            engine.createCollection("tenant-b", new CreateCollectionRequest("docs", 3, MetricType.COSINE, 1_000_000L));

            engine.upsert("tenant-a", "docs", new UpsertVectorsRequest(List.of(
                    new UpsertVector("alpha", new float[]{1.0F, 0.0F, 0.0F}, Map.of("tenant", "a"))
            )));
            engine.upsert("tenant-b", "docs", new UpsertVectorsRequest(List.of(
                    new UpsertVector("beta", new float[]{0.0F, 1.0F, 0.0F}, Map.of("tenant", "b"))
            )));

            assertEquals(List.of("alpha"), engine.search("tenant-a", "docs", new SearchRequest(
                    new float[]{1.0F, 0.0F, 0.0F},
                    10,
                    Map.of()
            )).hits().stream().map(hit -> hit.id()).toList());
            assertEquals(List.of("beta"), engine.search("tenant-b", "docs", new SearchRequest(
                    new float[]{0.0F, 1.0F, 0.0F},
                    10,
                    Map.of()
            )).hits().stream().map(hit -> hit.id()).toList());

            assertTrue(Files.exists(dataDir.resolve("tenants").resolve("tenant-a").resolve("collections").resolve("docs").resolve("collection.json")));
            assertTrue(Files.exists(dataDir.resolve("tenants").resolve("tenant-b").resolve("collections").resolve("docs").resolve("collection.json")));
        }

        try (VectorDatabaseEngine reopened = new VectorDatabaseEngine(dataDir, 1_000_000L)) {
            assertEquals(1, reopened.listCollections("tenant-a").size());
            assertEquals(1, reopened.listCollections("tenant-b").size());
            assertEquals(List.of("alpha"), reopened.search("tenant-a", "docs", new SearchRequest(
                    new float[]{1.0F, 0.0F, 0.0F},
                    10,
                    Map.of()
            )).hits().stream().map(hit -> hit.id()).toList());
            assertEquals(List.of("beta"), reopened.search("tenant-b", "docs", new SearchRequest(
                    new float[]{0.0F, 1.0F, 0.0F},
                    10,
                    Map.of()
            )).hits().stream().map(hit -> hit.id()).toList());
        }
    }

    @Test
    void deletesVectorsAndRecoversTombstonesAcrossRestart() throws Exception {
        try (VectorDatabaseEngine engine = new VectorDatabaseEngine(tempDir.resolve("delete-data"), 1024L)) {
            engine.createCollection(new CreateCollectionRequest("docs", 3, MetricType.COSINE, 1024L));
            engine.upsert("docs", new UpsertVectorsRequest(List.of(
                    new UpsertVector("alpha", new float[]{1.0F, 0.0F, 0.0F}, Map.of("tenant", "blue")),
                    new UpsertVector("beta", new float[]{0.0F, 1.0F, 0.0F}, Map.of("tenant", "red"))
            )));

            engine.delete("docs", new DeleteVectorsRequest(List.of("beta")));

            SearchResponse search = engine.search("docs", new SearchRequest(
                    new float[]{0.0F, 1.0F, 0.0F},
                    10,
                    Map.of()
            ));
            assertEquals(List.of("alpha"), search.hits().stream().map(hit -> hit.id()).toList());

            CollectionStats stats = engine.stats("docs");
            assertEquals(1L, stats.liveVectorCount());
            assertEquals(1L, stats.tombstoneCount());
        }

        try (VectorDatabaseEngine reopened = new VectorDatabaseEngine(tempDir.resolve("delete-data"), 1024L)) {
            SearchResponse recovered = reopened.search("docs", new SearchRequest(
                    new float[]{0.0F, 1.0F, 0.0F},
                    10,
                    Map.of()
            ));
            assertEquals(List.of("alpha"), recovered.hits().stream().map(hit -> hit.id()).toList());

            reopened.upsert("docs", new UpsertVectorsRequest(List.of(
                    new UpsertVector("beta", new float[]{0.0F, 1.0F, 0.0F}, Map.of("tenant", "red", "version", "v2"))
            )));

            SearchResponse restored = reopened.search("docs", new SearchRequest(
                    new float[]{0.0F, 1.0F, 0.0F},
                    10,
                    Map.of("tenant", "red")
            ));
            assertEquals(List.of("beta"), restored.hits().stream().map(hit -> hit.id()).toList());
            assertEquals(2L, reopened.stats("docs").liveVectorCount());
            assertEquals(0L, reopened.stats("docs").tombstoneCount());
        }
    }

    @Test
    void compactsSegmentsKeepingLatestLiveVersion() throws Exception {
        try (VectorDatabaseEngine engine = new VectorDatabaseEngine(tempDir.resolve("compact-live"), 1L)) {
            engine.createCollection(new CreateCollectionRequest("docs", 3, MetricType.COSINE, 1L));

            engine.upsert("docs", new UpsertVectorsRequest(List.of(
                    new UpsertVector("alpha", new float[]{1.0F, 0.0F, 0.0F}, Map.of("version", "v1"))
            )));
            waitFor(() -> engine.stats("docs").segmentCount() == 1, "first segment flush did not complete");

            engine.upsert("docs", new UpsertVectorsRequest(List.of(
                    new UpsertVector("alpha", new float[]{0.9F, 0.1F, 0.0F}, Map.of("version", "v2"))
            )));
            waitFor(() -> engine.stats("docs").segmentCount() == 2, "second segment flush did not complete");

            engine.compact("docs");

            CollectionStats compactedStats = engine.stats("docs");
            assertEquals(1, compactedStats.segmentCount());
            assertEquals(1L, compactedStats.liveVectorCount());
            assertEquals(0L, compactedStats.tombstoneCount());

            SearchResponse compacted = engine.search("docs", new SearchRequest(
                    new float[]{1.0F, 0.0F, 0.0F},
                    10,
                    Map.of()
            ));
            assertEquals(List.of("alpha"), compacted.hits().stream().map(hit -> hit.id()).toList());
            assertEquals("v2", compacted.hits().get(0).payload().get("version"));
        }

        try (VectorDatabaseEngine reopened = new VectorDatabaseEngine(tempDir.resolve("compact-live"), 1L)) {
            SearchResponse recovered = reopened.search("docs", new SearchRequest(
                    new float[]{1.0F, 0.0F, 0.0F},
                    10,
                    Map.of()
            ));
            assertEquals(List.of("alpha"), recovered.hits().stream().map(hit -> hit.id()).toList());
            assertEquals("v2", recovered.hits().get(0).payload().get("version"));
            assertEquals(1, reopened.stats("docs").segmentCount());
        }
    }

    @Test
    void compactsDeletedHistoryAway() throws Exception {
        try (VectorDatabaseEngine engine = new VectorDatabaseEngine(tempDir.resolve("compact-delete"), 1L)) {
            engine.createCollection(new CreateCollectionRequest("docs", 3, MetricType.COSINE, 1L));

            engine.upsert("docs", new UpsertVectorsRequest(List.of(
                    new UpsertVector("alpha", new float[]{1.0F, 0.0F, 0.0F}, Map.of("tenant", "blue"))
            )));
            waitFor(() -> engine.stats("docs").segmentCount() == 1, "live segment flush did not complete");

            engine.delete("docs", new DeleteVectorsRequest(List.of("alpha")));
            waitFor(() -> {
                CollectionStats stats = engine.stats("docs");
                return stats.segmentCount() == 2 || (stats.segmentCount() == 0 && stats.tombstoneCount() == 0L);
            }, "tombstone flush or auto compaction did not complete");

            engine.compact("docs");

            CollectionStats compacted = engine.stats("docs");
            assertEquals(0, compacted.segmentCount());
            assertEquals(0L, compacted.liveVectorCount());
            assertEquals(0L, compacted.tombstoneCount());

            SearchResponse empty = engine.search("docs", new SearchRequest(
                    new float[]{1.0F, 0.0F, 0.0F},
                    10,
                    Map.of()
            ));
            assertTrue(empty.hits().isEmpty());
        }

        try (VectorDatabaseEngine reopened = new VectorDatabaseEngine(tempDir.resolve("compact-delete"), 1L)) {
            CollectionStats stats = reopened.stats("docs");
            assertEquals(0, stats.segmentCount());
            assertEquals(0L, stats.liveVectorCount());
            assertEquals(0L, stats.tombstoneCount());
            assertTrue(reopened.search("docs", new SearchRequest(
                    new float[]{1.0F, 0.0F, 0.0F},
                    10,
                    Map.of()
            )).hits().isEmpty());
        }
    }

    @Test
    void flushesActiveMemtableOnDemand() throws Exception {
        Path dataDir = tempDir.resolve("manual-flush");
        try (VectorDatabaseEngine engine = new VectorDatabaseEngine(dataDir, 1_000_000L)) {
            engine.createCollection(new CreateCollectionRequest("docs", 3, MetricType.COSINE, 1_000_000L));
            engine.upsert("docs", new UpsertVectorsRequest(List.of(
                    new UpsertVector("alpha", new float[]{1.0F, 0.0F, 0.0F}, Map.of("tenant", "blue"))
            )));

            assertEquals(0, engine.stats("docs").segmentCount());

            engine.flush("docs");

            CollectionStats stats = engine.stats("docs");
            assertEquals(1, stats.segmentCount());
            assertFalse(stats.flushInProgress());
            assertEquals(List.of("alpha"), engine.search("docs", new SearchRequest(
                    new float[]{1.0F, 0.0F, 0.0F},
                    10,
                    Map.of()
            )).hits().stream().map(hit -> hit.id()).toList());
        }

        try (VectorDatabaseEngine reopened = new VectorDatabaseEngine(dataDir, 1_000_000L)) {
            assertEquals(1, reopened.stats("docs").segmentCount());
            assertEquals(List.of("alpha"), reopened.search("docs", new SearchRequest(
                    new float[]{1.0F, 0.0F, 0.0F},
                    10,
                    Map.of()
            )).hits().stream().map(hit -> hit.id()).toList());
        }
    }

    @Test
    void flushesUpdateHeavyMemtableBeforeByteThreshold() throws Exception {
        try (VectorDatabaseEngine engine = new VectorDatabaseEngine(tempDir.resolve("adaptive-flush"), 1_000_000L)) {
            engine.createCollection(new CreateCollectionRequest("docs", 3, MetricType.COSINE, 1_000_000L));

            for (int index = 0; index < 2_200; index++) {
                engine.upsert("docs", new UpsertVectorsRequest(List.of(
                        new UpsertVector("alpha", new float[]{1.0F, 0.0F, 0.0F}, Map.of("version", index))
                )));
            }

            waitFor(() -> engine.stats("docs").segmentCount() > 0, "update-heavy memtable did not flush adaptively");
        }
    }

    @Test
    void compactsDeleteHeavyHistoryWithoutManualTrigger() throws Exception {
        try (VectorDatabaseEngine engine = new VectorDatabaseEngine(tempDir.resolve("adaptive-compact"), 1L)) {
            engine.createCollection(new CreateCollectionRequest("docs", 3, MetricType.COSINE, 1L));

            engine.upsert("docs", new UpsertVectorsRequest(List.of(
                    new UpsertVector("alpha", new float[]{1.0F, 0.0F, 0.0F}, Map.of("tenant", "blue"))
            )));
            waitFor(() -> engine.stats("docs").segmentCount() == 1, "live segment flush did not complete");

            engine.delete("docs", new DeleteVectorsRequest(List.of("alpha")));

            waitFor(() -> {
                CollectionStats stats = engine.stats("docs");
                return stats.segmentCount() == 0 && stats.tombstoneCount() == 0L;
            }, "delete-heavy history did not compact automatically");
        }
    }

    @Test
    void partiallyUpdatesPayloadsAndRecoversAcrossRestart() throws Exception {
        Path dataDir = tempDir.resolve("partial-update");
        LinkedHashMap<String, Object> patch = new LinkedHashMap<>();
        patch.put("title", "Intro v2");
        patch.put("obsolete", null);
        patch.put("meta", Map.of("section", "basics", "published", true));

        try (VectorDatabaseEngine engine = new VectorDatabaseEngine(dataDir, 1_000_000L)) {
            engine.createCollection(new CreateCollectionRequest("docs", 3, MetricType.COSINE, 1_000_000L));
            engine.upsert("docs", new UpsertVectorsRequest(List.of(
                    new UpsertVector(
                            "alpha",
                            new float[]{1.0F, 0.0F, 0.0F},
                            Map.of(
                                    "title", "Intro",
                                    "obsolete", "legacy",
                                    "meta", Map.of("section", "draft", "priority", 1)
                            )
                    )
            )));

            engine.partialUpdate("docs", new PartialUpdateVectorsRequest(List.of(
                    new PartialUpdateVector("alpha", patch)
            )));

            SearchResponse updated = engine.search("docs", new SearchRequest(
                    new float[]{1.0F, 0.0F, 0.0F},
                    10,
                    Map.of("title", "Intro v2")
            ));
            assertEquals(List.of("alpha"), updated.hits().stream().map(hit -> hit.id()).toList());
            assertEquals("Intro v2", updated.hits().get(0).payload().get("title"));
            assertFalse(updated.hits().get(0).payload().containsKey("obsolete"));
            assertEquals(
                    Map.of("section", "basics", "priority", 1, "published", true),
                    updated.hits().get(0).payload().get("meta")
            );

            engine.flush("docs");
        }

        try (VectorDatabaseEngine reopened = new VectorDatabaseEngine(dataDir, 1_000_000L)) {
            SearchResponse recovered = reopened.search("docs", new SearchRequest(
                    new float[]{1.0F, 0.0F, 0.0F},
                    10,
                    Map.of("title", "Intro v2")
            ));
            assertEquals(List.of("alpha"), recovered.hits().stream().map(hit -> hit.id()).toList());
            assertEquals(
                    Map.of("section", "basics", "priority", 1, "published", true),
                    recovered.hits().get(0).payload().get("meta")
            );
        }
    }

    @Test
    void searchesLargeCollectionThroughGraphIndex() throws Exception {
        try (VectorDatabaseEngine engine = new VectorDatabaseEngine(tempDir.resolve("graph-index"), 1_000_000L)) {
            engine.createCollection(new CreateCollectionRequest("docs", 16, MetricType.COSINE, 1_000_000L));

            ArrayList<UpsertVector> vectors = new ArrayList<>();
            for (int index = 0; index < 320; index++) {
                float[] vector = new float[16];
                vector[index % vector.length] = 1.0F;
                vector[(index + 3) % vector.length] = 0.25F;
                String group = index % 2 == 0 ? "blue" : "red";
                vectors.add(new UpsertVector("doc-" + index, vector, Map.of("group", group, "bucket", index % 8)));
            }
            float[] target = new float[16];
            target[7] = 1.0F;
            target[9] = 0.5F;
            vectors.add(new UpsertVector("target", target, Map.of("group", "blue", "bucket", 9)));
            engine.upsert("docs", new UpsertVectorsRequest(vectors));

            SearchResponse response = engine.search("docs", new SearchRequest(target, 5, Map.of("group", "blue")));
            assertFalse(response.hits().isEmpty());
            assertEquals("target", response.hits().get(0).id());
        }
    }

    @Test
    void persistsAnnArtifactsAndSupportsAdvancedFiltersAfterRestart() throws Exception {
        Path dataDir = tempDir.resolve("persisted-ann");
        try (VectorDatabaseEngine engine = new VectorDatabaseEngine(dataDir, 24_000L)) {
            engine.createCollection(new CreateCollectionRequest("docs", 16, MetricType.COSINE, 24_000L));

            ArrayList<UpsertVector> vectors = new ArrayList<>();
            for (int index = 0; index < 320; index++) {
                float[] vector = new float[16];
                vector[index % vector.length] = 1.0F;
                vector[(index + 5) % vector.length] = 0.15F;
                String group = index % 2 == 0 ? "blue" : "red";
                Map<String, Object> payload = Map.of(
                        "group", group,
                        "bucket", index % 12,
                        "tags", List.of(group, index % 3 == 0 ? "hot" : "cold"),
                        "meta", Map.of("region", index % 5 == 0 ? "eu" : "us", "priority", index % 10)
                );
                vectors.add(new UpsertVector("doc-" + index, vector, payload));
            }
            float[] target = new float[16];
            target[7] = 1.0F;
            target[9] = 0.5F;
            vectors.add(new UpsertVector(
                    "target",
                    target,
                    Map.of(
                            "group", "blue",
                            "bucket", 9,
                            "tags", List.of("blue", "hot", "featured"),
                            "meta", Map.of("region", "eu", "priority", 9)
                    )
            ));
            engine.upsert("docs", new UpsertVectorsRequest(vectors));

            waitFor(() -> engine.stats("docs").segmentCount() == 1, "segment flush did not complete");

            SearchResponse initial = engine.search("docs", new SearchRequest(target, 5, Map.of("group", "blue")));
            assertFalse(initial.hits().isEmpty());
            assertEquals("target", initial.hits().get(0).id());
        }

        Path artifact;
        try (var paths = Files.list(dataDir.resolve("docs").resolve("segments"))) {
            artifact = paths
                    .filter(path -> path.getFileName().toString().endsWith(".ann"))
                    .findFirst()
                    .orElseThrow();
        }
        assertTrue(Files.size(artifact) > 0L);

        try (VectorDatabaseEngine reopened = new VectorDatabaseEngine(dataDir, 24_000L)) {
            SearchResponse filtered = reopened.search("docs", new SearchRequest(
                    new float[]{0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 1.0F, 0.0F, 0.5F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F},
                    10,
                    Map.of(
                            "$and", List.of(
                                    Map.of("group", Map.of("$in", List.of("blue", "green"))),
                                    Map.of("bucket", Map.of("$gte", 8, "$lte", 10)),
                                    Map.of("tags", Map.of("$contains", "featured")),
                                    Map.of("$or", List.of(
                                            Map.of("meta.region", "eu"),
                                            Map.of("meta.priority", Map.of("$gt", 8))
                                    )),
                                    Map.of("$not", Map.of("meta.priority", Map.of("$lt", 9)))
                            )
                    )
            ));
            assertEquals(List.of("target"), filtered.hits().stream().map(hit -> hit.id()).toList());
        }
    }

    @Test
    void backsUpAndRestoresCollectionSnapshots() throws Exception {
        Path dataDir = tempDir.resolve("backup-restore");
        Path backupDir = tempDir.resolve("backups");
        try (VectorDatabaseEngine engine = new VectorDatabaseEngine(dataDir, 1_000_000L)) {
            engine.createCollection(new CreateCollectionRequest("docs", 3, MetricType.COSINE, 1_000_000L));
            engine.upsert("docs", new UpsertVectorsRequest(List.of(
                    new UpsertVector("alpha", new float[]{1.0F, 0.0F, 0.0F}, Map.of("tenant", "blue"))
            )));

            engine.backupCollection("docs", "nightly", backupDir);

            engine.upsert("docs", new UpsertVectorsRequest(List.of(
                    new UpsertVector("beta", new float[]{0.0F, 1.0F, 0.0F}, Map.of("tenant", "red"))
            )));

            CollectionStats restored = engine.restoreCollection("docs", "docs-restore", "nightly", backupDir);
            assertEquals("docs-restore", restored.name());

            SearchResponse restoredSearch = engine.search("docs-restore", new SearchRequest(
                    new float[]{1.0F, 0.0F, 0.0F},
                    10,
                    Map.of()
            ));
            assertEquals(List.of("alpha"), restoredSearch.hits().stream().map(hit -> hit.id()).toList());
        }
    }

    @Test
    void recoversFromTruncatedWalTail() throws Exception {
        Path dataDir = tempDir.resolve("wal-recovery");
        try (VectorDatabaseEngine engine = new VectorDatabaseEngine(dataDir, 1_000_000L)) {
            engine.createCollection(new CreateCollectionRequest("docs", 3, MetricType.COSINE, 1_000_000L));
            engine.upsert("docs", new UpsertVectorsRequest(List.of(
                    new UpsertVector("alpha", new float[]{1.0F, 0.0F, 0.0F}, Map.of("tenant", "blue")),
                    new UpsertVector("beta", new float[]{0.0F, 1.0F, 0.0F}, Map.of("tenant", "red"))
            )));
        }

        Path activeWal = dataDir.resolve("docs").resolve("wal").resolve("active.wal");
        Files.write(activeWal, new byte[]{0x12, 0x34, 0x56, 0x78, 0x01}, StandardOpenOption.APPEND);

        try (VectorDatabaseEngine reopened = new VectorDatabaseEngine(dataDir, 1_000_000L)) {
            SearchResponse response = reopened.search("docs", new SearchRequest(
                    new float[]{1.0F, 0.0F, 0.0F},
                    10,
                    Map.of()
            ));
            assertEquals(List.of("alpha", "beta"), response.hits().stream().map(hit -> hit.id()).toList());
        }

        Path quarantine = dataDir.resolve("docs").resolve("quarantine");
        assertTrue(Files.list(quarantine).anyMatch(path -> path.getFileName().toString().startsWith("active.wal.wal-recovered")));
    }

    @Test
    void rejectsCorruptedSegmentFiles() throws Exception {
        Path dataDir = tempDir.resolve("segment-corruption");
        try (VectorDatabaseEngine engine = new VectorDatabaseEngine(dataDir, 1L)) {
            engine.createCollection(new CreateCollectionRequest("docs", 3, MetricType.COSINE, 1L));
            engine.upsert("docs", new UpsertVectorsRequest(List.of(
                    new UpsertVector("alpha", new float[]{1.0F, 0.0F, 0.0F}, Map.of("tenant", "blue"))
            )));
            waitFor(() -> engine.stats("docs").segmentCount() == 1, "segment flush did not complete");
        }

        Path segment = Files.list(dataDir.resolve("docs").resolve("segments")).findFirst().orElseThrow();
        byte[] bytes = Files.readAllBytes(segment);
        bytes[bytes.length - 1] ^= 0x01;
        Files.write(segment, bytes, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);

        assertThrows(Exception.class, () -> new VectorDatabaseEngine(dataDir, 1L));
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
