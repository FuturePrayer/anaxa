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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.CRC32;

public final class ImmutableSegment implements SearchableVectors, AutoCloseable {
    private static final int MAGIC = 0x53454731;
    static final int CURRENT_VERSION = 3;
    private static final int VERSION_LEGACY = 1;
    private static final int VERSION_TOMBSTONES = 2;
    static final int FLAG_TOMBSTONE = 1;
    private static final long HEADER_BYTES = Integer.BYTES + Integer.BYTES + Integer.BYTES + Integer.BYTES + Long.BYTES + Integer.BYTES;
    static final int FOOTER_MAGIC = 0x53454746;
    static final long FOOTER_BYTES = Integer.BYTES + Integer.BYTES + Long.BYTES;
    private static final long PREFETCH_INTERVAL_NANOS = TimeUnit.MILLISECONDS.toNanos(250L);
    private static final int CHECKSUM_CHUNK_BYTES = 1024 * 1024;
    private static final ValueLayout.OfInt INT_LAYOUT = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN);
    private static final ValueLayout.OfLong LONG_LAYOUT = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN);
    private static final ValueLayout.OfFloat FLOAT_LAYOUT = ValueLayout.JAVA_FLOAT_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN);
    private static final ValueLayout.OfFloat VECTOR_FLOAT_LAYOUT = ValueLayout.JAVA_FLOAT_UNALIGNED.withOrder(ByteOrder.nativeOrder());

    private final Path path;
    private final CollectionDefinition definition;
    private final long generation;
    private final Arena arena;
    private final MemorySegment mappedSegment;
    private final List<SegmentEntry> entries;
    private final Map<String, SegmentEntry> entriesById;
    private final AtomicLong lastPrefetchNanos;

    private ImmutableSegment(
            Path path,
            CollectionDefinition definition,
            long generation,
            Arena arena,
            MemorySegment mappedSegment,
            List<SegmentEntry> entries,
            Map<String, SegmentEntry> entriesById
    ) {
        this.path = path;
        this.definition = definition;
        this.generation = generation;
        this.arena = arena;
        this.mappedSegment = mappedSegment;
        this.entries = entries;
        this.entriesById = entriesById;
        this.lastPrefetchNanos = new AtomicLong(0L);
    }

    public static ImmutableSegment load(Path path, CollectionDefinition definition) throws IOException {
        Arena arena = Arena.ofShared();
        try (FileChannel channel = FileChannel.open(path)) {
            MemorySegment mappedSegment = channel.map(FileChannel.MapMode.READ_ONLY, 0L, channel.size(), arena);
            Header header = readHeader(mappedSegment, definition);
            ArrayList<SegmentEntry> entries = new ArrayList<>(header.entryCount());
            HashMap<String, SegmentEntry> entriesById = new HashMap<>(Math.max(16, header.entryCount() * 2));
            long dataLimit = header.version() == CURRENT_VERSION
                    ? validateFooterAndResolveDataLimit(mappedSegment)
                    : mappedSegment.byteSize();

            long offset = HEADER_BYTES;
            long vectorBytes = (long) definition.dimension() * Float.BYTES;
            for (int index = 0; index < header.entryCount(); index++) {
                int flags = 0;
                if (header.version() >= VERSION_TOMBSTONES) {
                    flags = mappedSegment.get(INT_LAYOUT, offset);
                    offset += Integer.BYTES;
                }

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

                boolean tombstone = (flags & FLAG_TOMBSTONE) != 0;
                long vectorOffset = tombstone ? -1L : offset;
                if (!tombstone) {
                    offset += vectorBytes;
                }

                byte[] payloadBytes = mappedSegment.asSlice(offset, payloadLength).toArray(ValueLayout.JAVA_BYTE);
                offset += payloadLength;

                SegmentEntry entry = new SegmentEntry(id, sequence, tombstone, norm, vectorOffset, JsonSupport.readMap(payloadBytes));
                entries.add(entry);
                entriesById.put(id, entry);
            }
            if (offset != dataLimit) {
                throw new IOException("Segment entry data length mismatch");
            }

            return new ImmutableSegment(
                    path,
                    definition,
                    header.generation(),
                    arena,
                    mappedSegment,
                    List.copyOf(entries),
                    Map.copyOf(entriesById)
            );
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

    public SegmentEntry entry(String id) {
        return entriesById.get(id);
    }

    public byte[] vectorBytes(SegmentEntry entry) {
        if (entry.tombstone()) {
            return new byte[0];
        }
        return mappedSegment.asSlice(entry.vectorOffsetBytes(), (long) definition.dimension() * Float.BYTES)
                .toArray(ValueLayout.JAVA_BYTE);
    }

    public float[] vector(SegmentEntry entry) {
        if (entry.tombstone()) {
            return new float[0];
        }
        float[] vector = new float[definition.dimension()];
        long offset = entry.vectorOffsetBytes();
        for (int index = 0; index < vector.length; index++) {
            // 向量字节来自 MemTable 的 native-order off-heap 布局；这里必须保持一致，
            // 否则重启后 partial update 会把错误向量重新写入 WAL/segment，影响生产数据可靠性。
            vector[index] = mappedSegment.get(VECTOR_FLOAT_LAYOUT, offset + (long) index * Float.BYTES);
        }
        return vector;
    }

    @Override
    public String sourceId() {
        return definition.tenantId() + "/" + definition.name() + "/segment-%020d".formatted(generation);
    }

    @Override
    public long searchStateVersion() {
        return generation;
    }

    @Override
    public Path searchArtifactPath() {
        return path.resolveSibling(path.getFileName().toString() + ".ann");
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
    public long approximateBytes() {
        return mappedSegment.byteSize();
    }

    @Override
    public void prefetch(long budgetBytes) {
        if (budgetBytes <= 0L || mappedSegment.byteSize() > budgetBytes) {
            return;
        }

        long now = System.nanoTime();
        long previous = lastPrefetchNanos.get();
        if (previous != 0L && now - previous < PREFETCH_INTERVAL_NANOS && mappedSegment.isLoaded()) {
            return;
        }
        if (lastPrefetchNanos.compareAndSet(previous, now)) {
            mappedSegment.load();
        }
    }

    @Override
    public void scan(VectorEntryConsumer consumer) {
        for (SegmentEntry entry : entries) {
            consumer.accept(
                    entry.id(),
                    entry.sequence(),
                    entry.tombstone(),
                    entry.norm(),
                    entry.tombstone() ? MemorySegment.NULL : mappedSegment,
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

        if (magic != MAGIC || (version != VERSION_LEGACY && version != VERSION_TOMBSTONES && version != CURRENT_VERSION)) {
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
        return new Header(version, generation, entryCount);
    }

    private static long validateFooterAndResolveDataLimit(MemorySegment mappedSegment) throws IOException {
        if (mappedSegment.byteSize() < HEADER_BYTES + FOOTER_BYTES) {
            throw new IOException("Segment file too small to contain footer");
        }

        long footerOffset = mappedSegment.byteSize() - FOOTER_BYTES;
        int footerMagic = mappedSegment.get(INT_LAYOUT, footerOffset);
        int expectedChecksum = mappedSegment.get(INT_LAYOUT, footerOffset + Integer.BYTES);
        long dataLength = mappedSegment.get(LONG_LAYOUT, footerOffset + Integer.BYTES * 2L);
        if (footerMagic != FOOTER_MAGIC) {
            throw new IOException("Invalid segment footer");
        }
        if (dataLength != footerOffset) {
            throw new IOException("Segment footer data length mismatch");
        }

        CRC32 crc32 = new CRC32();
        byte[] checksumBuffer = new byte[(int) Math.min(CHECKSUM_CHUNK_BYTES, Math.max(1L, dataLength))];
        long offset = 0L;
        while (offset < dataLength) {
            int chunkBytes = (int) Math.min(checksumBuffer.length, dataLength - offset);
            MemorySegment.copy(
                    mappedSegment,
                    ValueLayout.JAVA_BYTE,
                    offset,
                    MemorySegment.ofArray(checksumBuffer),
                    ValueLayout.JAVA_BYTE,
                    0,
                    chunkBytes
            );
            // 分块校验避免加载大 segment 时一次性复制整个 mmap 文件，可靠性保持不变但显著降低堆峰值。
            crc32.update(checksumBuffer, 0, chunkBytes);
            offset += chunkBytes;
        }
        if ((int) crc32.getValue() != expectedChecksum) {
            throw new IOException("Segment checksum mismatch");
        }
        return dataLength;
    }

    private record Header(int version, long generation, int entryCount) {
    }
}
