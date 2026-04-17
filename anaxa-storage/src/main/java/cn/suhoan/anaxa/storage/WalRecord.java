package cn.suhoan.anaxa.storage;

import cn.suhoan.anaxa.common.util.Copying;

import java.util.Map;
import java.util.Objects;

public record WalRecord(String id, float[] vector, Map<String, Object> payload, long sequence, Kind kind) {
    public enum Kind {
        LIVE,
        TOMBSTONE,
        PAYLOAD_PATCH
    }

    public WalRecord {
        id = Objects.requireNonNull(id, "id");
        kind = Objects.requireNonNull(kind, "kind");
        vector = vector == null ? new float[0] : vector;
        payload = payload == null || payload.isEmpty() ? Map.of() : payload;
        switch (kind) {
            case LIVE -> {
            }
            case TOMBSTONE -> {
                if (vector.length > 0) {
                    throw new IllegalArgumentException("Tombstone WAL records must not carry vectors");
                }
                payload = Map.of();
            }
            case PAYLOAD_PATCH -> {
                if (vector.length > 0) {
                    throw new IllegalArgumentException("Payload patch WAL records must not carry vectors");
                }
                if (payload.isEmpty()) {
                    throw new IllegalArgumentException("Payload patch WAL records must not be empty");
                }
            }
        }
    }

    public static WalRecord live(String id, float[] vector, Map<String, Object> payload, long sequence) {
        return liveTrusted(id, Copying.vector(vector), Copying.payload(payload), sequence);
    }

    public static WalRecord liveTrusted(String id, float[] vector, Map<String, Object> payload, long sequence) {
        return new WalRecord(id, vector, payload, sequence, Kind.LIVE);
    }

    public static WalRecord tombstone(String id, long sequence) {
        return new WalRecord(id, new float[0], Map.of(), sequence, Kind.TOMBSTONE);
    }

    public static WalRecord payloadPatch(String id, Map<String, Object> payload, long sequence) {
        return payloadPatchTrusted(id, Copying.payload(payload), sequence);
    }

    public static WalRecord payloadPatchTrusted(String id, Map<String, Object> payload, long sequence) {
        return new WalRecord(id, new float[0], payload, sequence, Kind.PAYLOAD_PATCH);
    }

    public boolean tombstone() {
        return kind == Kind.TOMBSTONE;
    }

    public boolean payloadPatch() {
        return kind == Kind.PAYLOAD_PATCH;
    }
}
