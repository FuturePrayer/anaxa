package cn.suhoan.anaxa.common.util;

import cn.suhoan.anaxa.common.model.UpsertVector;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BinaryVectorStreamsTest {
    @Test
    void writesAndReadsBinaryVectorBatches() {
        byte[] encoded = BinaryVectorStreams.writeBatch(3, List.of(
                new UpsertVector("alpha", new float[]{1.0F, 0.0F, 0.0F}, Map.of("tenant", "blue")),
                new UpsertVector("beta", new float[]{0.0F, 1.0F, 0.0F}, Map.of("tenant", "red"))
        ));
        ArrayList<UpsertVector> decoded = new ArrayList<>();

        long count = BinaryVectorStreams.readBatch(new ByteArrayInputStream(encoded), 3, decoded::add);

        assertEquals(2L, count);
        assertEquals(List.of("alpha", "beta"), decoded.stream().map(UpsertVector::id).toList());
        assertEquals("red", decoded.get(1).payload().get("tenant"));
    }

    @Test
    void rejectsDimensionMismatch() {
        byte[] encoded = BinaryVectorStreams.writeBatch(3, List.of(
                new UpsertVector("alpha", new float[]{1.0F, 0.0F, 0.0F}, Map.of())
        ));

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> BinaryVectorStreams.readBatch(new ByteArrayInputStream(encoded), 4, vector -> {
                })
        );

        assertEquals("Expected vector dimension 4 but binary batch declares 3", exception.getMessage());
    }
}
