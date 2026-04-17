package cn.suhoan.anaxa.benchmark;

import cn.suhoan.anaxa.common.model.MetricType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnvironmentBenchmarkRunnerTest {
    @TempDir
    Path tempDir;

    @Test
    void parsesRemoteModeArguments() {
        BenchmarkConfig config = BenchmarkConfig.fromArgs(new String[]{
                "--base-url=http://127.0.0.1:8080",
                "--profile=markdown-kb",
                "--tenant-id=team-a",
                "--collection-prefix=Env Bench"
        });

        assertFalse(config.embeddedServer());
        assertEquals("http://127.0.0.1:8080", config.baseUrl());
        assertEquals(ScenarioPreset.MARKDOWN_KB, config.preset());
        assertEquals("team-a", config.tenantId());
        assertEquals("env-bench", config.collectionPrefix());
    }

    @Test
    void runsEmbeddedBenchmarkAndRendersReport() throws Exception {
        BenchmarkConfig config = new BenchmarkConfig(
                null,
                "127.0.0.1",
                0,
                tempDir,
                false,
                null,
                "bench-team",
                4_096L,
                1_000_000,
                100_000,
                1_000L,
                ScenarioPreset.QUICK,
                "tiny-bench",
                Duration.ofSeconds(30L)
        );
        List<BenchmarkScenario> scenarios = List.of(
                new BenchmarkScenario("tiny-none", "tiny", 8, MetricType.COSINE, 4_096L, 64, 16, 10, 40, 2, 5, 8, true, PrepareMode.NONE),
                new BenchmarkScenario("tiny-flush", "tiny", 8, MetricType.COSINE, 4_096L, 64, 16, 10, 40, 2, 5, 8, true, PrepareMode.FLUSH)
        );

        EnvironmentBenchmarkRunner.BenchmarkReport report = new EnvironmentBenchmarkRunner(config, scenarios).run();
        String text = report.render();

        assertTrue(text.contains("[environment]"));
        assertTrue(text.contains("[summary]"));
        assertTrue(text.contains("tiny-none"));
        assertTrue(text.contains("tiny-flush"));
        assertTrue(text.contains("[observations]"));
    }
}
