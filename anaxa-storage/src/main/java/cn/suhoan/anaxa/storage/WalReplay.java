package cn.suhoan.anaxa.storage;

import cn.suhoan.anaxa.common.json.JsonSupport;
import cn.suhoan.anaxa.common.model.CollectionDefinition;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class WalReplay {
    private static final int MAGIC = 0x57414C31;
    private static final int VERSION_LEGACY = 1;
    private static final int VERSION_TOMBSTONES = 2;
    private static final int VERSION_CHECKSUMS = 3;

    private WalReplay() {
    }

    public static List<WalRecord> readAll(Path path, CollectionDefinition definition) throws IOException {
        return readResult(path, definition).records();
    }

    public static Header readHeader(Path path) throws IOException {
        try (InputStream input = Files.newInputStream(path);
             DataInputStream data = new DataInputStream(new BufferedInputStream(input))) {
            int magic = data.readInt();
            int version = data.readInt();
            int dimension = data.readInt();
            if (magic != MAGIC) {
                throw new IOException("Invalid WAL header");
            }
            return new Header(version, dimension);
        }
    }

    public static ReplayResult readResult(Path path, CollectionDefinition definition) throws IOException {
        if (!Files.exists(path) || Files.size(path) == 0L) {
            return new ReplayResult(List.of(), VERSION_CHECKSUMS, false);
        }

        ArrayList<WalRecord> records = new ArrayList<>();
        try (InputStream input = Files.newInputStream(path);
             DataInputStream data = new DataInputStream(new BufferedInputStream(input))) {
            int version = validateHeader(data, definition.dimension());
            boolean recoveredTail = false;
            while (true) {
                Integer bodyLength = tryReadInt(data);
                if (bodyLength == null) {
                    break;
                }
                if (bodyLength < Long.BYTES + Integer.BYTES * 2) {
                    recoveredTail = true;
                    break;
                }

                byte[] body = data.readNBytes(bodyLength);
                if (body.length < bodyLength) {
                    recoveredTail = true;
                    break;
                }

                if (version == VERSION_CHECKSUMS) {
                    Integer checksum = tryReadInt(data);
                    if (checksum == null || checksum != WalAppender.checksum(body)) {
                        recoveredTail = true;
                        break;
                    }
                }

                records.add(readRecord(ByteBuffer.wrap(body).order(ByteOrder.BIG_ENDIAN), definition, version));
            }
            return new ReplayResult(List.copyOf(records), version, recoveredTail);
        }
    }

    public static void replay(Path path, CollectionDefinition definition, java.util.function.Consumer<WalRecord> consumer)
            throws IOException {
        for (WalRecord record : readResult(path, definition).records()) {
            consumer.accept(record);
        }
    }

    private static Integer tryReadInt(DataInputStream data) throws IOException {
        try {
            return data.readInt();
        } catch (EOFException ignored) {
            return null;
        }
    }

    private static WalRecord readRecord(ByteBuffer buffer, CollectionDefinition definition, int version) {
        int flags = 0;
        if (version >= VERSION_TOMBSTONES) {
            flags = buffer.getInt();
        }

        long sequence = buffer.getLong();
        int idLength = buffer.getInt();
        int payloadLength = buffer.getInt();

        byte[] idBytes = new byte[idLength];
        buffer.get(idBytes);

        boolean tombstone = (flags & WalAppender.FLAG_TOMBSTONE) != 0;
        float[] vector = tombstone ? new float[0] : new float[definition.dimension()];
        if (!tombstone) {
            for (int index = 0; index < vector.length; index++) {
                vector[index] = buffer.getFloat();
            }
        }

        byte[] payloadBytes = new byte[payloadLength];
        buffer.get(payloadBytes);

        return tombstone
                ? WalRecord.tombstone(new String(idBytes, StandardCharsets.UTF_8), sequence)
                : WalRecord.live(
                new String(idBytes, StandardCharsets.UTF_8),
                vector,
                JsonSupport.readMap(payloadBytes),
                sequence
        );
    }

    private static int validateHeader(DataInputStream data, int expectedDimension) throws IOException {
        int magic = data.readInt();
        int version = data.readInt();
        int dimension = data.readInt();
        if (magic != MAGIC || (version != VERSION_LEGACY && version != VERSION_TOMBSTONES && version != VERSION_CHECKSUMS)) {
            throw new IOException("Invalid WAL header");
        }
        if (dimension != expectedDimension) {
            throw new IOException(
                    "WAL dimension %d does not match collection dimension %d".formatted(dimension, expectedDimension)
            );
        }
        return version;
    }

    public record Header(int version, int dimension) {
    }

    public record ReplayResult(List<WalRecord> records, int version, boolean recoveredTail) {
    }
}
