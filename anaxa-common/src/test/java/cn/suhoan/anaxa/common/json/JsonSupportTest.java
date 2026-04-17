package cn.suhoan.anaxa.common.json;

import cn.suhoan.anaxa.common.model.CreateCollectionRequest;
import cn.suhoan.anaxa.common.model.MetricType;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JsonSupportTest {
    @Test
    void readsTypedJsonFromInputStream() {
        byte[] body = """
                {"name":"docs","dimension":16,"metric":"COSINE","flushThresholdBytes":1024}
                """.getBytes(StandardCharsets.UTF_8);

        CreateCollectionRequest request = JsonSupport.read(new ByteArrayInputStream(body), CreateCollectionRequest.class);

        assertEquals("docs", request.name());
        assertEquals(16, request.dimension());
        assertEquals(MetricType.COSINE, request.metric());
        assertEquals(1024L, request.flushThresholdBytes());
    }

    @Test
    void estimatesPayloadBytesWithoutMaterializingJsonBytes() {
        Map<String, Object> payload = Map.of(
                "name", "docs",
                "count", 12,
                "tags", List.of("hot", "blue"),
                "nested", Map.of("enabled", true, "bucket", 7)
        );

        assertEquals(JsonSupport.writeBytes(payload).length, JsonSupport.estimateBytes(payload));
    }
}
