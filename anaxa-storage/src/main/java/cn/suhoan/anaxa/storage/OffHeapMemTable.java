package cn.suhoan.anaxa.storage;

import cn.suhoan.anaxa.common.model.CollectionDefinition;
import cn.suhoan.anaxa.common.model.MetricType;
import cn.suhoan.anaxa.common.util.Copying;
import cn.suhoan.anaxa.index.SearchableVectors;
import cn.suhoan.anaxa.index.VectorEntryConsumer;
import cn.suhoan.anaxa.index.VectorMetricScorer;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

public final class OffHeapMemTable implements SearchableVectors, AutoCloseable {
    private final CollectionDefinition definition;
    private final long generation;
    private final Arena arena;
    private final ConcurrentHashMap<String, MemTableEntry> entries;
    private final LongAdder approximateBytes;

    public OffHeapMemTable(CollectionDefinition definition, long generation) {
        this.definition = definition;
        this.generation = generation;
        this.arena = Arena.ofShared();
        this.entries = new ConcurrentHashMap<>();
        this.approximateBytes = new LongAdder();
    }

    public long generation() {
        return generation;
    }

    public void upsert(String id, float[] vector, Map<String, Object> payload, long sequence) {
        validateDimension(vector);
        MemorySegment vectorSegment = arena.allocate((long) vector.length * Float.BYTES, Float.BYTES);
        MemorySegment.copy(
                MemorySegment.ofArray(vector),
                ValueLayout.JAVA_FLOAT,
                0,
                vectorSegment,
                ValueLayout.JAVA_FLOAT,
                0,
                vector.length
        );

        Map<String, Object> stablePayload = Copying.payload(payload);
        int footprint = estimateFootprint(id, stablePayload, vector.length);
        MemTableEntry entry = new MemTableEntry(
                id,
                sequence,
                VectorMetricScorer.norm(vector),
                vectorSegment,
                stablePayload,
                footprint
        );

        MemTableEntry previous = entries.put(id, entry);
        approximateBytes.add(footprint);
        if (previous != null) {
            approximateBytes.add(-previous.byteFootprint());
        }
    }

    public long approximateBytes() {
        return approximateBytes.sum();
    }

    public List<MemTableEntry> snapshotEntries() {
        return entries.values().stream()
                .sorted(Comparator.comparingLong(MemTableEntry::sequence))
                .toList();
    }

    @Override
    public String sourceId() {
        return "memtable-%020d".formatted(generation);
    }

    @Override
    public int dimension() {
        return definition.dimension();
    }

    @Override
    public MetricType metric() {
        return definition.metric();
    }

    @Override
    public int size() {
        return entries.size();
    }

    @Override
    public void scan(VectorEntryConsumer consumer) {
        entries.forEach((id, entry) -> consumer.accept(
                id,
                entry.sequence(),
                entry.norm(),
                entry.vectorSegment(),
                0L,
                entry.payload()
        ));
    }

    @Override
    public void close() {
        arena.close();
    }

    private void validateDimension(float[] vector) {
        if (vector.length != definition.dimension()) {
            throw new IllegalArgumentException(
                    "Expected vector dimension %d but got %d".formatted(definition.dimension(), vector.length)
            );
        }
    }

    private int estimateFootprint(String id, Map<String, Object> payload, int dimension) {
        int payloadBytes = cn.suhoan.anaxa.common.json.JsonSupport.writeBytes(payload).length;
        return id.getBytes(java.nio.charset.StandardCharsets.UTF_8).length + payloadBytes + (dimension * Float.BYTES);
    }
}
