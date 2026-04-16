package cn.suhoan.anaxa.storage;

import cn.suhoan.anaxa.common.util.Copying;

import java.util.Arrays;
import java.util.Map;

public record BufferedSegmentEntry(
        String id,
        long sequence,
        boolean tombstone,
        float norm,
        byte[] vectorBytes,
        Map<String, Object> payload
) implements SegmentWritableEntry {
    public BufferedSegmentEntry {
        vectorBytes = Arrays.copyOf(vectorBytes, vectorBytes.length);
        payload = Copying.payload(payload);
    }
}
