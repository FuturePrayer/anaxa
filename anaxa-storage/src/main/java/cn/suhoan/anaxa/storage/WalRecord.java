package cn.suhoan.anaxa.storage;

import cn.suhoan.anaxa.common.util.Copying;

import java.util.Map;

public record WalRecord(String id, float[] vector, Map<String, Object> payload, long sequence) {
    public WalRecord {
        vector = Copying.vector(vector);
        payload = Copying.payload(payload);
    }
}
