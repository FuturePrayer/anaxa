package cn.suhoan.anaxa.storage;

import cn.suhoan.anaxa.common.util.Copying;

import java.util.Map;

public record SegmentEntry(String id, long sequence, float norm, long vectorOffsetBytes, Map<String, Object> payload) {
    public SegmentEntry {
        payload = Copying.payload(payload);
    }
}
