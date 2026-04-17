package cn.suhoan.anaxa.common.json;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.InputStream;
import java.lang.reflect.Array;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
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

    public static <T> T read(InputStream input, Class<T> type) {
        try {
            return MAPPER.readValue(input, type);
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

    public static int estimateBytes(Object value) {
        return estimateValueBytes(value == null ? Map.of() : value);
    }

    private static int estimateValueBytes(Object value) {
        if (value == null) {
            return 4;
        }
        return switch (value) {
            case String string -> estimateStringBytes(string);
            case Number number -> number.toString().length();
            case Boolean bool -> bool ? 4 : 5;
            case Enum<?> enumeration -> estimateStringBytes(enumeration.name());
            case Map<?, ?> map -> estimateMapBytes(map);
            case Iterable<?> iterable -> estimateIterableBytes(iterable.iterator());
            default -> value.getClass().isArray()
                    ? estimateArrayBytes(value)
                    : estimateStringBytes(String.valueOf(value));
        };
    }

    private static int estimateMapBytes(Map<?, ?> map) {
        int bytes = 2;
        boolean first = true;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!first) {
                bytes++;
            }
            first = false;
            bytes += estimateStringBytes(String.valueOf(entry.getKey()));
            bytes++;
            bytes += estimateValueBytes(entry.getValue());
        }
        return bytes;
    }

    private static int estimateIterableBytes(Iterator<?> iterator) {
        int bytes = 2;
        boolean first = true;
        while (iterator.hasNext()) {
            if (!first) {
                bytes++;
            }
            first = false;
            bytes += estimateValueBytes(iterator.next());
        }
        return bytes;
    }

    private static int estimateArrayBytes(Object array) {
        int length = Array.getLength(array);
        int bytes = 2;
        for (int index = 0; index < length; index++) {
            if (index > 0) {
                bytes++;
            }
            bytes += estimateValueBytes(Array.get(array, index));
        }
        return bytes;
    }

    private static int estimateStringBytes(String value) {
        int bytes = 2;
        for (int index = 0; index < value.length(); ) {
            int codePoint = value.codePointAt(index);
            index += Character.charCount(codePoint);
            bytes += switch (codePoint) {
                case '"', '\\' -> 2;
                case '\b', '\f', '\n', '\r', '\t' -> 2;
                default -> codePoint <= 0x1F
                        ? 6
                        : new String(Character.toChars(codePoint)).getBytes(StandardCharsets.UTF_8).length;
            };
        }
        return bytes;
    }
}
