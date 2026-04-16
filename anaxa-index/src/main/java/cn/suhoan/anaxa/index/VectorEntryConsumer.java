package cn.suhoan.anaxa.index;

import java.lang.foreign.MemorySegment;
import java.util.Map;

@FunctionalInterface
public interface VectorEntryConsumer {
    void accept(
            String id,
            long sequence,
            boolean tombstone,
            float norm,
            MemorySegment vectorSegment,
            long vectorOffsetBytes,
            Map<String, Object> payload
    );
}
