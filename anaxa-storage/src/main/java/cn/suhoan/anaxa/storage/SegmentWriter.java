package cn.suhoan.anaxa.storage;

import cn.suhoan.anaxa.common.json.JsonSupport;
import cn.suhoan.anaxa.common.model.CollectionDefinition;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.CheckedOutputStream;

public final class SegmentWriter {
    private static final int MAGIC = 0x53454731;

    private SegmentWriter() {
    }

    public static ImmutableSegment write(CollectionPaths paths, CollectionDefinition definition, OffHeapMemTable memTable)
            throws IOException {
        return write(paths, definition, memTable.generation(), memTable.snapshotEntries());
    }

    public static ImmutableSegment write(
            CollectionPaths paths,
            CollectionDefinition definition,
            long generation,
            List<? extends SegmentWritableEntry> entries
    ) throws IOException {
        if (entries.isEmpty()) {
            return null;
        }

        Files.createDirectories(paths.segmentsDirectory());
        Path segmentPath = paths.segmentFile(generation);
        Path temporaryPath = segmentPath.resolveSibling(segmentPath.getFileName() + ".tmp");

        try (BufferedOutputStream buffered = new BufferedOutputStream(
                Files.newOutputStream(temporaryPath, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE))) {
            CRC32 crc32 = new CRC32();
            CheckedOutputStream checked = new CheckedOutputStream(buffered, crc32);
            DataOutputStream output = new DataOutputStream(checked);
            long dataLength = 0L;

            output.writeInt(MAGIC);
            dataLength += Integer.BYTES;
            output.writeInt(ImmutableSegment.CURRENT_VERSION);
            dataLength += Integer.BYTES;
            output.writeInt(definition.dimension());
            dataLength += Integer.BYTES;
            output.writeInt(definition.metric().ordinal());
            dataLength += Integer.BYTES;
            output.writeLong(generation);
            dataLength += Long.BYTES;
            output.writeInt(entries.size());
            dataLength += Integer.BYTES;

            for (SegmentWritableEntry entry : entries) {
                byte[] idBytes = entry.id().getBytes(StandardCharsets.UTF_8);
                byte[] vectorBytes = entry.vectorBytes();
                byte[] payloadBytes = JsonSupport.writeBytes(entry.payload());
                if (!entry.tombstone() && vectorBytes.length != definition.dimension() * Float.BYTES) {
                    throw new IllegalArgumentException(
                            "Segment entry %s has %d vector bytes, expected %d"
                                    .formatted(entry.id(), vectorBytes.length, definition.dimension() * Float.BYTES)
                    );
                }

                output.writeInt(entry.tombstone() ? ImmutableSegment.FLAG_TOMBSTONE : 0);
                dataLength += Integer.BYTES;
                output.writeLong(entry.sequence());
                dataLength += Long.BYTES;
                output.writeFloat(entry.norm());
                dataLength += Float.BYTES;
                output.writeInt(idBytes.length);
                dataLength += Integer.BYTES;
                output.writeInt(payloadBytes.length);
                dataLength += Integer.BYTES;
                output.write(idBytes);
                dataLength += idBytes.length;
                if (!entry.tombstone()) {
                    output.write(vectorBytes);
                    dataLength += vectorBytes.length;
                }
                output.write(payloadBytes);
                dataLength += payloadBytes.length;
            }
            output.flush();

            DataOutputStream footer = new DataOutputStream(buffered);
            footer.writeInt(ImmutableSegment.FOOTER_MAGIC);
            footer.writeInt((int) crc32.getValue());
            footer.writeLong(dataLength);
            footer.flush();
        }

        moveIntoPlace(temporaryPath, segmentPath);
        return ImmutableSegment.load(segmentPath, definition);
    }

    private static void moveIntoPlace(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
