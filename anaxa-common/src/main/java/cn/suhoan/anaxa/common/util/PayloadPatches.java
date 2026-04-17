package cn.suhoan.anaxa.common.util;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class PayloadPatches {
    private PayloadPatches() {
    }

    public static Map<String, Object> merge(Map<String, Object> base, Map<String, Object> patch) {
        LinkedHashMap<String, Object> merged = deepMutableMap(base == null ? Map.of() : base);
        applyPatch(merged, patch == null ? Map.of() : patch);
        return Collections.unmodifiableMap(merged);
    }

    private static void applyPatch(LinkedHashMap<String, Object> target, Map<String, Object> patch) {
        for (Map.Entry<String, Object> entry : patch.entrySet()) {
            String key = Objects.requireNonNull(entry.getKey(), "payload key");
            Object patchValue = entry.getValue();
            if (patchValue == null) {
                target.remove(key);
                continue;
            }

            Object currentValue = target.get(key);
            if (currentValue instanceof Map<?, ?> currentMap && patchValue instanceof Map<?, ?> patchMap) {
                LinkedHashMap<String, Object> nested = deepMutableMap(castMap(currentMap));
                applyPatch(nested, castMap(patchMap));
                target.put(key, Collections.unmodifiableMap(nested));
                continue;
            }
            target.put(key, immutableValue(patchValue));
        }
    }

    private static LinkedHashMap<String, Object> deepMutableMap(Map<String, ?> source) {
        LinkedHashMap<String, Object> copy = new LinkedHashMap<>(source.size());
        source.forEach((key, value) -> copy.put(Objects.requireNonNull(key, "payload key"), immutableValue(value)));
        return copy;
    }

    private static Object immutableValue(Object value) {
        if (value == null
                || value instanceof String
                || value instanceof Number
                || value instanceof Boolean
                || value instanceof Enum<?>) {
            return value;
        }
        if (value instanceof Map<?, ?> map) {
            return Collections.unmodifiableMap(deepMutableMap(castMap(map)));
        }
        if (value instanceof Iterable<?> iterable) {
            ArrayList<Object> copy = new ArrayList<>();
            for (Object item : iterable) {
                copy.add(immutableValue(item));
            }
            return Collections.unmodifiableList(copy);
        }
        if (value.getClass().isArray()) {
            int length = Array.getLength(value);
            ArrayList<Object> copy = new ArrayList<>(length);
            for (int index = 0; index < length; index++) {
                copy.add(immutableValue(Array.get(value, index)));
            }
            return Collections.unmodifiableList(copy);
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> value) {
        return (Map<String, Object>) value;
    }
}
