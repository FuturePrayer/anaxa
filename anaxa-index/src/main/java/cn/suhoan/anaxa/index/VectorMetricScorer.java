package cn.suhoan.anaxa.index;

import cn.suhoan.anaxa.common.model.MetricType;
import jdk.incubator.vector.FloatVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;

public final class VectorMetricScorer {
    private static final VectorSpecies<Float> SPECIES = FloatVector.SPECIES_PREFERRED;
    private static final ByteOrder BYTE_ORDER = ByteOrder.nativeOrder();
    private static final ValueLayout.OfFloat FLOAT_LAYOUT = ValueLayout.JAVA_FLOAT_UNALIGNED.withOrder(BYTE_ORDER);

    private VectorMetricScorer() {
    }

    public static float norm(float[] vector) {
        double sum = 0.0D;
        for (float value : vector) {
            sum += value * value;
        }
        return (float) Math.sqrt(sum);
    }

    public static float score(
            MetricType metric,
            float[] queryVector,
            float queryNorm,
            MemorySegment candidateSegment,
            long candidateOffsetBytes,
            float candidateNorm
    ) {
        return switch (metric) {
            case COSINE -> cosine(queryVector, queryNorm, candidateSegment, candidateOffsetBytes, candidateNorm);
            case L2 -> -l2Squared(queryVector, candidateSegment, candidateOffsetBytes);
        };
    }

    private static float cosine(
            float[] queryVector,
            float queryNorm,
            MemorySegment candidateSegment,
            long candidateOffsetBytes,
            float candidateNorm
    ) {
        if (queryNorm == 0.0F || candidateNorm == 0.0F) {
            return 0.0F;
        }
        return dot(queryVector, candidateSegment, candidateOffsetBytes) / (queryNorm * candidateNorm);
    }

    private static float l2Squared(float[] queryVector, MemorySegment candidateSegment, long candidateOffsetBytes) {
        int speciesLength = SPECIES.length();
        int upperBound = SPECIES.loopBound(queryVector.length);
        FloatVector laneSum = FloatVector.zero(SPECIES);

        int index = 0;
        for (; index < upperBound; index += speciesLength) {
            FloatVector query = FloatVector.fromArray(SPECIES, queryVector, index);
            FloatVector candidate = FloatVector.fromMemorySegment(
                    SPECIES,
                    candidateSegment,
                    candidateOffsetBytes + (long) index * Float.BYTES,
                    BYTE_ORDER
            );
            FloatVector delta = query.sub(candidate);
            laneSum = laneSum.add(delta.mul(delta));
        }

        float sum = laneSum.reduceLanes(VectorOperators.ADD);
        for (; index < queryVector.length; index++) {
            float delta = queryVector[index] - candidateSegment.get(
                    FLOAT_LAYOUT,
                    candidateOffsetBytes + (long) index * Float.BYTES
            );
            sum += delta * delta;
        }
        return sum;
    }

    private static float dot(float[] queryVector, MemorySegment candidateSegment, long candidateOffsetBytes) {
        int speciesLength = SPECIES.length();
        int upperBound = SPECIES.loopBound(queryVector.length);
        FloatVector laneSum = FloatVector.zero(SPECIES);

        int index = 0;
        for (; index < upperBound; index += speciesLength) {
            FloatVector query = FloatVector.fromArray(SPECIES, queryVector, index);
            FloatVector candidate = FloatVector.fromMemorySegment(
                    SPECIES,
                    candidateSegment,
                    candidateOffsetBytes + (long) index * Float.BYTES,
                    BYTE_ORDER
            );
            laneSum = laneSum.add(query.mul(candidate));
        }

        float sum = laneSum.reduceLanes(VectorOperators.ADD);
        for (; index < queryVector.length; index++) {
            sum += queryVector[index] * candidateSegment.get(
                    FLOAT_LAYOUT,
                    candidateOffsetBytes + (long) index * Float.BYTES
            );
        }
        return sum;
    }
}
