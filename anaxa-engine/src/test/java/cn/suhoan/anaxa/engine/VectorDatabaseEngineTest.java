package cn.suhoan.anaxa.engine;

import cn.suhoan.anaxa.common.model.CollectionStats;
import cn.suhoan.anaxa.common.model.CreateCollectionRequest;
import cn.suhoan.anaxa.common.model.MetricType;
import cn.suhoan.anaxa.common.model.SearchResponse;
import cn.suhoan.anaxa.common.model.SearchRequest;
import cn.suhoan.anaxa.common.model.UpsertVector;
import cn.suhoan.anaxa.common.model.UpsertVectorsRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
