package cn.suhoan.anaxa.storage;

import cn.suhoan.anaxa.common.json.JsonSupport;
import cn.suhoan.anaxa.common.model.CollectionDefinition;
import cn.suhoan.anaxa.common.model.MetricType;
import cn.suhoan.anaxa.index.SearchableVectors;
import cn.suhoan.anaxa.index.VectorEntryConsumer;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.channels.FileChannel;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class ImmutableSegment implements SearchableVectors, AutoCloseable {
    private static final int MAGIC = 0x53454731;
    private static final int VERSION = 1;
    private static final long HEADER_BYTES = Integer.BYTES + Integer.BYTES + Integer.BYTES + Integer.BYTES + Long.BYTES + Integer.BYTES;
    private static final ValueLayout.OfInt INT_LAYOUT = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN);
    private static final ValueLayout.OfLong LONG_LAYOUT = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN);
    private static final ValueLayout.OfFloat FLOAT_LAYOUT = ValueLayout.JAVA_FLOAT_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN);

    private final Path path;
    private final CollectionDefinition definition;
    private final long generation;
    private final Arena arena;
    private final MemorySegment mappedSegment;
    private final List<SegmentEntry> entries;

    private ImmutableSegment(
            Path path,
            CollectionDefinition definition,
            long generation,
            Arena arena,
            MemorySegment mappedSegment,
            List<SegmentEntry> entries
    ) {
        this.path = path;
        this.definition = definition;
        this.generation = generation;
        this.arena = arena;
        this.mappedSegment = mappedSegment;
        this.entries = entries;
    }

    public static ImmutableSegment load(Path path, CollectionDefinition definition) throws IOException {
        Arena arena = Arena.ofShared();
        try (FileChannel channel = FileChannel.open(path)) {
            MemorySegment mappedSegment = channel.map(FileChannel.MapMode.READ_ONLY, 0L, channel.size(), arena);
            Header header = readHeader(mappedSegment, definition);
            ArrayList<SegmentEntry> entries = new ArrayList<>(header.entryCount());

            long offset = HEADER_BYTES;
            long vectorBytes = (long) definition.dimension() * Float.BYTES;
            for (int index = 0; index < header.entryCount(); index++) {
                long sequence = mappedSegment.get(LONG_LAYOUT, offset);
                offset += Long.BYTES;

                float norm = mappedSegment.get(FLOAT_LAYOUT, offset);
                offset += Float.BYTES;

                int idLength = mappedSegment.get(INT_LAYOUT, offset);
                offset += Integer.BYTES;

                int payloadLength = mappedSegment.get(INT_LAYOUT, offset);
                offset += Integer.BYTES;

                String id = new String(mappedSegment.asSlice(offset, idLength).toArray(ValueLayout.JAVA_BYTE), StandardCharsets.UTF_8);
                offset += idLength;

                long vectorOffset = offset;
                offset += vectorBytes;

                byte[] payloadBytes = mappedSegment.asSlice(offset, payloadLength).toArray(ValueLayout.JAVA_BYTE);
                offset += payloadLength;

                entries.add(new SegmentEntry(id, sequence, norm, vectorOffset, JsonSupport.readMap(payloadBytes)));
            }

            return new ImmutableSegment(path, definition, header.generation(), arena, mappedSegment, List.copyOf(entries));
        } catch (Throwable throwable) {
            arena.close();
            throw throwable;
        }
    }

    public Path path() {
        return path;
    }

    public long generation() {
        return generation;
    }

    public List<SegmentEntry> entries() {
        return entries;
    }

    @Override
    public String sourceId() {
        return path.getFileName().toString();
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
        for (SegmentEntry entry : entries) {
            consumer.accept(
                    entry.id(),
                    entry.sequence(),
                    entry.norm(),
                    mappedSegment,
                    entry.vectorOffsetBytes(),
                    entry.payload()
            );
        }
    }

    @Override
    public void close() {
        arena.close();
    }

    private static Header readHeader(MemorySegment mappedSegment, CollectionDefinition definition) throws IOException {
        long offset = 0L;
        int magic = mappedSegment.get(INT_LAYOUT, offset);
        offset += Integer.BYTES;
        int version = mappedSegment.get(INT_LAYOUT, offset);
        offset += Integer.BYTES;
        int dimension = mappedSegment.get(INT_LAYOUT, offset);
        offset += Integer.BYTES;
        int metricOrdinal = mappedSegment.get(INT_LAYOUT, offset);
        offset += Integer.BYTES;
        long generation = mappedSegment.get(LONG_LAYOUT, offset);
        offset += Long.BYTES;
        int entryCount = mappedSegment.get(INT_LAYOUT, offset);

        if (magic != MAGIC || version != VERSION) {
            throw new IOException("Invalid segment header");
        }
        if (dimension != definition.dimension()) {
            throw new IOException(
                    "Segment dimension %d does not match collection dimension %d"
                            .formatted(dimension, definition.dimension())
            );
        }
        if (metricOrdinal < 0 || metricOrdinal >= MetricType.values().length) {
            throw new IOException("Invalid metric ordinal in segment header: " + metricOrdinal);
        }
        if (MetricType.values()[metricOrdinal] != definition.metric()) {
            throw new IOException("Segment metric does not match collection metric");
        }
        return new Header(generation, entryCount);
    }

    private record Header(long generation, int entryCount) {
    }
}
