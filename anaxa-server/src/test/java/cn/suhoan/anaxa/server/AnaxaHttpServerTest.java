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
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

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

    private HttpResponse<String> sendJson(HttpClient client, String uri, String method, Object body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(uri))
                .header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(JsonSupport.writeString(body)));
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}
