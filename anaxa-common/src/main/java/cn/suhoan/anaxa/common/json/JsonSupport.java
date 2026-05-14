package cn.suhoan.anaxa.common.json;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Array;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Shared JSON serialization helpers used by AnaxaDB modules and the SDK.
 */
public final class JsonSupport {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final ObjectMapper MAPPER = JsonMapper.builder().findAndAddModules().build();

    private JsonSupport() {
    }

    /**
     * Returns the shared Jackson mapper instance.
     *
     * @return shared object mapper
     */
    public static ObjectMapper mapper() {
        return MAPPER;
    }

    /**
     * Serializes a value to UTF-8 JSON bytes.
     *
     * @param value value to serialize, or an empty object when null
     * @return serialized JSON bytes
     */
    public static byte[] writeBytes(Object value) {
        try {
            return MAPPER.writeValueAsBytes(value == null ? Map.of() : value);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to serialize JSON payload", exception);
        }
    }

    /**
     * Serializes a value to a JSON string.
     *
     * @param value value to serialize, or an empty object when null
     * @return serialized JSON string
     */
    public static String writeString(Object value) {
        try {
            return MAPPER.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to serialize JSON payload", exception);
        }
    }

    /**
     * Deserializes JSON bytes into a Java type.
     *
     * @param bytes JSON bytes
     * @param type target type
     * @param <T> target type
     * @return deserialized value
     */
    public static <T> T read(byte[] bytes, Class<T> type) {
        try {
            return MAPPER.readValue(bytes, type);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Failed to deserialize JSON body", exception);
        }
    }

    /**
     * Deserializes a JSON stream into a Java type.
     *
     * @param input JSON input stream
     * @param type target type
     * @param <T> target type
     * @return deserialized value
     */
    public static <T> T read(InputStream input, Class<T> type) {
        try {
            return MAPPER.readValue(input, type);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Failed to deserialize JSON body", exception);
        }
    }

    /**
     * Reads newline-delimited JSON values from a stream.
     *
     * @param input NDJSON input stream
     * @param type target item type
     * @param consumer receiver for each decoded item
     * @param <T> target item type
     */
    public static <T> void readNdjson(InputStream input, Class<T> type, Consumer<T> consumer) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank()) {
                    continue;
                }
                try {
                    consumer.accept(MAPPER.readValue(line, type));
                } catch (Exception exception) {
                    throw new IllegalArgumentException("Failed to deserialize NDJSON line " + lineNumber, exception);
                }
            }
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("Failed to deserialize NDJSON body", exception);
        }
    }

    /**
     * Deserializes JSON bytes into a string-keyed map.
     *
     * @param bytes JSON bytes, or empty bytes for an empty map
     * @return deserialized payload map
     */
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

    /**
     * Estimates the number of bytes needed to encode a value as JSON.
     *
     * @param value value to estimate
     * @return estimated JSON byte size
     */
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
