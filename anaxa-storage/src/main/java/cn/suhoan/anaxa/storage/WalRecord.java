package cn.suhoan.anaxa.storage;

import cn.suhoan.anaxa.common.util.Copying;

import java.util.Map;

public record WalRecord(String id, float[] vector, Map<String, Object> payload, long sequence, boolean tombstone) {
    public WalRecord {
        vector = Copying.vector(vector);
        payload = Copying.payload(payload);
    }

    public static WalRecord live(String id, float[] vector, Map<String, Object> payload, long sequence) {
        return new WalRecord(id, vector, payload, sequence, false);
    }

    public static WalRecord tombstone(String id, long sequence) {
        return new WalRecord(id, new float[0], Map.of(), sequence, true);
    }
}
