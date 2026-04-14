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
    private static final int VERSION = 1;

    private WalReplay() {
    }

    public static List<WalRecord> readAll(Path path, CollectionDefinition definition) throws IOException {
        ArrayList<WalRecord> records = new ArrayList<>();
        replay(path, definition, records::add);
        return records;
    }

    public static void replay(Path path, CollectionDefinition definition, java.util.function.Consumer<WalRecord> consumer)
            throws IOException {
        if (!Files.exists(path) || Files.size(path) == 0L) {
            return;
        }

        try (InputStream input = Files.newInputStream(path);
             DataInputStream data = new DataInputStream(new BufferedInputStream(input))) {
            validateHeader(data, definition.dimension());
            while (true) {
                Integer bodyLength = tryReadInt(data);
                if (bodyLength == null) {
                    return;
                }
                byte[] body = data.readNBytes(bodyLength);
                if (body.length < bodyLength) {
                    return;
                }

                ByteBuffer buffer = ByteBuffer.wrap(body).order(ByteOrder.BIG_ENDIAN);
                long sequence = buffer.getLong();
                int idLength = buffer.getInt();
                int payloadLength = buffer.getInt();

                byte[] idBytes = new byte[idLength];
                buffer.get(idBytes);

                float[] vector = new float[definition.dimension()];
                for (int index = 0; index < vector.length; index++) {
                    vector[index] = buffer.getFloat();
                }

                byte[] payloadBytes = new byte[payloadLength];
                buffer.get(payloadBytes);

                consumer.accept(new WalRecord(
                        new String(idBytes, StandardCharsets.UTF_8),
                        vector,
                        JsonSupport.readMap(payloadBytes),
                        sequence
                ));
            }
        }
    }

    private static Integer tryReadInt(DataInputStream data) throws IOException {
        try {
            return data.readInt();
        } catch (EOFException ignored) {
            return null;
        }
    }

    private static void validateHeader(DataInputStream data, int expectedDimension) throws IOException {
        int magic = data.readInt();
        int version = data.readInt();
        int dimension = data.readInt();
        if (magic != MAGIC || version != VERSION) {
            throw new IOException("Invalid WAL header");
        }
        if (dimension != expectedDimension) {
            throw new IOException(
                    "WAL dimension %d does not match collection dimension %d".formatted(dimension, expectedDimension)
            );
        }
    }
}
