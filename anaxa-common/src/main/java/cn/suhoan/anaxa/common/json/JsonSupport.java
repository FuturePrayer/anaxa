package cn.suhoan.anaxa.common.json;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

public final class JsonSupport {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final ObjectMapper MAPPER = JsonMapper.builder().findAndAddModules().build();

    private JsonSupport() {
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    public static byte[] writeBytes(Object value) {
        try {
            return MAPPER.writeValueAsBytes(value == null ? Map.of() : value);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to serialize JSON payload", exception);
        }
    }

    public static String writeString(Object value) {
        try {
            return MAPPER.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to serialize JSON payload", exception);
        }
    }

    public static <T> T read(byte[] bytes, Class<T> type) {
        try {
            return MAPPER.readValue(bytes, type);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Failed to deserialize JSON body", exception);
        }
    }

    public static Map<String, Object> readMap(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return Map.of();
        }
        try {
            return MAPPER.readValue(bytes, MAP_TYPE);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Failed to deserialize JSON map", exception);
        }
    }
}
