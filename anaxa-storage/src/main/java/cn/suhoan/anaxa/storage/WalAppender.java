package cn.suhoan.anaxa.storage;

import cn.suhoan.anaxa.common.json.JsonSupport;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.CRC32;

public final class WalAppender implements AutoCloseable {
    private static final int MAGIC = 0x57414C31;
    public static final int CURRENT_VERSION = 4;
    static final int FLAG_TOMBSTONE = 1;
    static final int FLAG_PAYLOAD_PATCH = 1 << 1;
    private static final int HEADER_BYTES = Integer.BYTES * 3;

    private final Path path;
    private final FileChannel channel;
    private final int dimension;

    private WalAppender(Path path, FileChannel channel, int dimension) {
        this.path = path;
        this.channel = channel;
        this.dimension = dimension;
    }

    public static WalAppender open(Path path, int dimension) throws IOException {
        Files.createDirectories(path.getParent());
        FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
        WalAppender appender = new WalAppender(path, channel, dimension);
        appender.initializeHeader();
        return appender;
    }

    public Path path() {
        return path;
    }

    public synchronized void appendAll(List<WalRecord> records) throws IOException {
        if (records.isEmpty()) {
            return;
        }

        for (WalRecord record : records) {
            writeRecord(record);
        }
        channel.force(false);
    }

    @Override
    public synchronized void close() throws IOException {
        channel.close();
    }

    private void initializeHeader() throws IOException {
        if (channel.size() == 0L) {
            ByteBuffer header = ByteBuffer.allocate(HEADER_BYTES)
                    .putInt(MAGIC)
                    .putInt(CURRENT_VERSION)
                    .putInt(dimension);
            header.flip();
            writeFully(header);
            channel.force(true);
        } else {
            ByteBuffer header = ByteBuffer.allocate(HEADER_BYTES);
            channel.position(0L);
            while (header.hasRemaining() && channel.read(header) > 0) {
                // read header fully
            }
            header.flip();
            if (header.remaining() != HEADER_BYTES
                    || header.getInt() != MAGIC
                    || header.getInt() != CURRENT_VERSION
                    || header.getInt() != dimension) {
                throw new IOException("Invalid WAL header for " + path);
            }
        }
        channel.position(channel.size());
    }

    private void writeRecord(WalRecord record) throws IOException {
        if (!record.tombstone() && !record.payloadPatch() && record.vector().length != dimension) {
            throw new IllegalArgumentException(
                    "WAL record dimension %d does not match collection dimension %d"
                            .formatted(record.vector().length, dimension)
            );
        }

        byte[] idBytes = record.id().getBytes(StandardCharsets.UTF_8);
        byte[] payloadBytes = JsonSupport.writeBytes(record.payload());
        int vectorBytes = record.tombstone() || record.payloadPatch() ? 0 : (dimension * Float.BYTES);
        int bodyLength = Integer.BYTES
                + Long.BYTES
                + Integer.BYTES
                + Integer.BYTES
                + idBytes.length
                + vectorBytes
                + payloadBytes.length;

        ByteBuffer buffer = ByteBuffer.allocate(Integer.BYTES + bodyLength + Integer.BYTES);
        buffer.putInt(bodyLength);
        int bodyStart = buffer.position();
        int flags = 0;
        if (record.tombstone()) {
            flags |= FLAG_TOMBSTONE;
        }
        if (record.payloadPatch()) {
            flags |= FLAG_PAYLOAD_PATCH;
        }
        buffer.putInt(flags);
        buffer.putLong(record.sequence());
        buffer.putInt(idBytes.length);
        buffer.putInt(payloadBytes.length);
        buffer.put(idBytes);
        if (!record.tombstone() && !record.payloadPatch()) {
            for (float value : record.vector()) {
                buffer.putFloat(value);
            }
        }
        buffer.put(payloadBytes);
        int checksum = checksum(buffer.array(), bodyStart, bodyLength);
        buffer.putInt(checksum);
        buffer.flip();
        writeFully(buffer);
    }

    private void writeFully(ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) {
            channel.write(buffer);
        }
    }

    static int checksum(byte[] body) {
        CRC32 crc32 = new CRC32();
        crc32.update(body);
        return (int) crc32.getValue();
    }

    static int checksum(byte[] body, int offset, int length) {
        CRC32 crc32 = new CRC32();
        crc32.update(body, offset, length);
        return (int) crc32.getValue();
    }
}
