package cn.suhoan.anaxa.storage;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Map;

public record MemTableEntry(
        String id,
        long sequence,
        float norm,
        MemorySegment vectorSegment,
        Map<String, Object> payload,
        int byteFootprint
) {
    public WalRecord toWalRecord() {
        return new WalRecord(id, vectorSegment.toArray(ValueLayout.JAVA_FLOAT), payload, sequence);
    }
}
