package cn.suhoan.anaxa.common.util;

import cn.suhoan.anaxa.common.json.JsonSupport;
import cn.suhoan.anaxa.common.model.UpsertVector;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Encoder and decoder for AnaxaDB binary vector batch payloads.
 */
public final class BinaryVectorStreams {
    private static final int MAGIC = 0x41584231;
    private static final int VERSION = 1;

    private BinaryVectorStreams() {
    }

    /**
     * Reads a binary vector batch and sends each vector to a consumer.
     *
     * @param input binary batch input stream
     * @param expectedDimension expected vector dimension
     * @param consumer receiver for decoded vectors
     * @return number of decoded records
     */
    public static long readBatch(InputStream input, int expectedDimension, Consumer<UpsertVector> consumer) {
        if (expectedDimension <= 0) {
            throw new IllegalArgumentException("expectedDimension must be positive");
        }
        try (DataInputStream data = new DataInputStream(new BufferedInputStream(input))) {
            int magic;
            try {
                magic = data.readInt();
            } catch (EOFException exception) {
                throw new IllegalArgumentException("Binary vector batch body must not be empty", exception);
            }
            if (magic != MAGIC) {
                throw new IllegalArgumentException("Invalid binary vector batch header");
            }
            int version = data.readInt();
            if (version != VERSION) {
                throw new IllegalArgumentException("Unsupported binary vector batch version: " + version);
            }
            int encodedDimension = data.readInt();
            if (encodedDimension != expectedDimension) {
                throw new IllegalArgumentException(
                        "Expected vector dimension %d but binary batch declares %d"
                                .formatted(expectedDimension, encodedDimension)
                );
            }

            long recordCount = 0L;
            while (true) {
                int idLength;
                try {
                    idLength = data.readInt();
                } catch (EOFException endOfStream) {
                    break;
                }
                int payloadLength = data.readInt();
                if (idLength <= 0) {
                    throw new IllegalArgumentException("Binary vector id length must be positive");
                }
                if (payloadLength < 0) {
                    throw new IllegalArgumentException("Binary vector payload length must not be negative");
                }

                byte[] idBytes = new byte[idLength];
                data.readFully(idBytes);
                float[] vector = new float[expectedDimension];
                for (int index = 0; index < expectedDimension; index++) {
                    vector[index] = data.readFloat();
                }

                byte[] payloadBytes = new byte[payloadLength];
                data.readFully(payloadBytes);
                Map<String, Object> payload = payloadLength == 0 ? Map.of() : JsonSupport.readMap(payloadBytes);
                consumer.accept(new UpsertVector(new String(idBytes, StandardCharsets.UTF_8), vector, payload));
                recordCount++;
            }

            if (recordCount == 0L) {
                throw new IllegalArgumentException("Binary vector batch must contain at least one record");
            }
            return recordCount;
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (EOFException exception) {
            throw new IllegalArgumentException("Unexpected end of binary vector batch", exception);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Failed to read binary vector batch", exception);
        }
    }

    /**
     * Writes vectors into the AnaxaDB binary batch format.
     *
     * @param dimension expected vector dimension
     * @param vectors vectors to encode
     * @return encoded binary batch bytes
     */
    public static byte[] writeBatch(int dimension, Iterable<UpsertVector> vectors) {
        if (dimension <= 0) {
            throw new IllegalArgumentException("dimension must be positive");
        }
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (DataOutputStream data = new DataOutputStream(new BufferedOutputStream(output))) {
                data.writeInt(MAGIC);
                data.writeInt(VERSION);
                data.writeInt(dimension);
                long recordCount = 0L;
                for (UpsertVector vector : vectors) {
                    if (vector.vector().length != dimension) {
                        throw new IllegalArgumentException(
                                "Expected vector dimension %d but got %d".formatted(dimension, vector.vector().length)
                        );
                    }
                    byte[] idBytes = vector.id().getBytes(StandardCharsets.UTF_8);
                    byte[] payloadBytes = vector.payload() == null || vector.payload().isEmpty()
                            ? new byte[0]
                            : JsonSupport.writeBytes(vector.payload());
                    data.writeInt(idBytes.length);
                    data.writeInt(payloadBytes.length);
                    data.write(idBytes);
                    for (float value : vector.vector()) {
                        data.writeFloat(value);
                    }
                    data.write(payloadBytes);
                    recordCount++;
                }
                if (recordCount == 0L) {
                    throw new IllegalArgumentException("vectors must not be empty");
                }
            }
            return output.toByteArray();
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to write binary vector batch", exception);
        }
    }
}
