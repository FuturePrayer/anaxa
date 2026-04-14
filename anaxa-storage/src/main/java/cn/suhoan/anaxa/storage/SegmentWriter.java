package cn.suhoan.anaxa.storage;

import cn.suhoan.anaxa.common.json.JsonSupport;
import cn.suhoan.anaxa.common.model.CollectionDefinition;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.foreign.ValueLayout;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.charset.StandardCharsets;
import java.util.List;

public final class SegmentWriter {
    private static final int MAGIC = 0x53454731;
    private static final int VERSION = 1;

    private SegmentWriter() {
    }

    public static ImmutableSegment write(CollectionPaths paths, CollectionDefinition definition, OffHeapMemTable memTable)
            throws IOException {
        List<MemTableEntry> entries = memTable.snapshotEntries();
        if (entries.isEmpty()) {
            return null;
        }

        Files.createDirectories(paths.segmentsDirectory());
        Path segmentPath = paths.segmentFile(memTable.generation());
        Path temporaryPath = segmentPath.resolveSibling(segmentPath.getFileName() + ".tmp");

        try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(
                Files.newOutputStream(temporaryPath, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)))) {
            output.writeInt(MAGIC);
            output.writeInt(VERSION);
            output.writeInt(definition.dimension());
            output.writeInt(definition.metric().ordinal());
            output.writeLong(memTable.generation());
            output.writeInt(entries.size());

            for (MemTableEntry entry : entries) {
                byte[] idBytes = entry.id().getBytes(StandardCharsets.UTF_8);
                byte[] vectorBytes = entry.vectorSegment().toArray(ValueLayout.JAVA_BYTE);
                byte[] payloadBytes = JsonSupport.writeBytes(entry.payload());

                output.writeLong(entry.sequence());
                output.writeFloat(entry.norm());
                output.writeInt(idBytes.length);
                output.writeInt(payloadBytes.length);
                output.write(idBytes);
                output.write(vectorBytes);
                output.write(payloadBytes);
            }
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
