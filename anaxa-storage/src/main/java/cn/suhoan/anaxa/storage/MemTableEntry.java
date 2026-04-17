package cn.suhoan.anaxa.storage;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Map;

public record MemTableEntry(
        String id,
        long sequence,
        boolean tombstone,
        float norm,
        MemorySegment vectorSegment,
        Map<String, Object> payload,
        int byteFootprint
) implements SegmentWritableEntry {
    private static final byte[] EMPTY_VECTOR_BYTES = new byte[0];

    public WalRecord toWalRecord() {
        if (tombstone) {
            return WalRecord.tombstone(id, sequence);
        }
        return WalRecord.liveTrusted(id, vectorSegment.toArray(ValueLayout.JAVA_FLOAT), payload, sequence);
    }

    @Override
    public byte[] vectorBytes() {
        return tombstone ? EMPTY_VECTOR_BYTES : vectorSegment.toArray(ValueLayout.JAVA_BYTE);
    }

    public float[] vector() {
        return tombstone ? new float[0] : vectorSegment.toArray(ValueLayout.JAVA_FLOAT);
    }
}
