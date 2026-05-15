package cn.suhoan.anaxa.server;

import cn.suhoan.anaxa.common.json.JsonSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerConfigTest {
    @TempDir
    Path tempDir;

    @Test
    void loadsConfigFileAndAllowsCommandLineOverrides() throws Exception {
        Path configFile = tempDir.resolve("anaxa-config.json");
        Path dataDir = tempDir.resolve("data-from-file");
        Files.writeString(configFile, JsonSupport.writeString(Map.of(
                "host", "127.0.0.1",
                "port", 31000,
                "dataDirectory", dataDir.toString(),
                "apiKeys", List.of("from-file"),
                "allowOpenAccess", false,
                "engine", Map.of(
                        "maxConcurrentSourceSearches", 8,
                        "warmupYieldPollMillis", 5,
                        "foregroundSearchesPerSourceSearch", 4,
                        "minAdaptiveSourceSearches", 2,
                        "adaptiveRecoverySearches", 9
                )
        )));

        ServerConfig config = ServerConfig.fromArgs(new String[] {
                "--config=" + configFile,
                "--port=0",
                "--allow-open-access=true",
                "--max-concurrent-source-searches=6"
        });

        assertEquals("127.0.0.1", config.host());
        assertEquals(0, config.port());
        assertEquals(dataDir, config.dataDirectory());
        assertEquals(6, config.engineOptions().maxConcurrentSourceSearches());
        assertEquals(5L, config.engineOptions().warmupYieldPollMillis());
        assertEquals(4, config.engineOptions().foregroundSearchesPerSourceSearch());
        assertEquals(2, config.engineOptions().minAdaptiveSourceSearches());
        assertEquals(9, config.engineOptions().adaptiveRecoverySearches());
        assertTrue(config.allowOpenAccess());
    }

    @Test
    void writesConfigTemplate() throws Exception {
        Path template = tempDir.resolve("nested").resolve("anaxa-config.json");
        ServerConfig.writeTemplate(template);

        String content = Files.readString(template);
        assertTrue(content.contains("\"engine\""));
        assertTrue(content.contains("\"maxConcurrentSourceSearches\""));
    }
}
