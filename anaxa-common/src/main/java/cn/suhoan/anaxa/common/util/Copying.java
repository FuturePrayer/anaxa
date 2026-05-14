package cn.suhoan.anaxa.common.util;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Defensive-copy helpers for mutable request and response values.
 */
public final class Copying {
    private Copying() {
    }

    /**
     * Copies a vector array.
     *
     * @param value vector values
     * @return copied vector values
     */
    public static float[] vector(float[] value) {
        return Arrays.copyOf(value, value.length);
    }

    /**
     * Copies payload data into an immutable map.
     *
     * @param payload payload map, or null for an empty payload
     * @return immutable payload map
     */
    public static Map<String, Object> payload(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(payload));
    }

    /**
     * Copies a list into an immutable list.
     *
     * @param values source values, or null for an empty list
     * @param <T> item type
     * @return immutable list
     */
    public static <T> List<T> immutableList(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
