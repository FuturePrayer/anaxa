package cn.suhoan.anaxa.index;

import cn.suhoan.anaxa.common.model.MetricType;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;

public final class VectorMetricScorer {
    private static final ByteOrder BYTE_ORDER = ByteOrder.nativeOrder();
    private static final ValueLayout.OfFloat FLOAT_LAYOUT = ValueLayout.JAVA_FLOAT_UNALIGNED.withOrder(BYTE_ORDER);

    private VectorMetricScorer() {
    }

    public static float norm(float[] vector) {
        float sum0 = 0.0F;
        float sum1 = 0.0F;
        float sum2 = 0.0F;
        float sum3 = 0.0F;
        int index = 0;
        int upperBound = vector.length & ~3;
        for (; index < upperBound; index += 4) {
            float v0 = vector[index];
            float v1 = vector[index + 1];
            float v2 = vector[index + 2];
            float v3 = vector[index + 3];
            sum0 += v0 * v0;
            sum1 += v1 * v1;
            sum2 += v2 * v2;
            sum3 += v3 * v3;
        }
        float sum = (sum0 + sum1) + (sum2 + sum3);
        for (; index < vector.length; index++) {
            float value = vector[index];
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

    public static float score(
            MetricType metric,
            float[] queryVector,
            float queryNorm,
            float[] candidateVector,
            float candidateNorm
    ) {
        return switch (metric) {
            case COSINE -> cosine(queryVector, queryNorm, candidateVector, candidateNorm);
            case L2 -> -l2Squared(queryVector, candidateVector);
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

    private static float cosine(
            float[] queryVector,
            float queryNorm,
            float[] candidateVector,
            float candidateNorm
    ) {
        if (queryNorm == 0.0F || candidateNorm == 0.0F) {
            return 0.0F;
        }
        return dot(queryVector, candidateVector) / (queryNorm * candidateNorm);
    }

    private static float l2Squared(float[] queryVector, MemorySegment candidateSegment, long candidateOffsetBytes) {
        float sum0 = 0.0F;
        float sum1 = 0.0F;
        float sum2 = 0.0F;
        float sum3 = 0.0F;
        int index = 0;
        int upperBound = queryVector.length & ~3;
        long offset = candidateOffsetBytes;
        for (; index < upperBound; index += 4, offset += 4L * Float.BYTES) {
            float d0 = queryVector[index] - candidateSegment.get(FLOAT_LAYOUT, offset);
            float d1 = queryVector[index + 1] - candidateSegment.get(FLOAT_LAYOUT, offset + Float.BYTES);
            float d2 = queryVector[index + 2] - candidateSegment.get(FLOAT_LAYOUT, offset + 2L * Float.BYTES);
            float d3 = queryVector[index + 3] - candidateSegment.get(FLOAT_LAYOUT, offset + 3L * Float.BYTES);
            sum0 += d0 * d0;
            sum1 += d1 * d1;
            sum2 += d2 * d2;
            sum3 += d3 * d3;
        }

        float sum = (sum0 + sum1) + (sum2 + sum3);
        for (; index < queryVector.length; index++) {
            float delta = queryVector[index] - candidateSegment.get(
                    FLOAT_LAYOUT,
                    offset
            );
            sum += delta * delta;
            offset += Float.BYTES;
        }
        return sum;
    }

    private static float dot(float[] queryVector, MemorySegment candidateSegment, long candidateOffsetBytes) {
        float sum0 = 0.0F;
        float sum1 = 0.0F;
        float sum2 = 0.0F;
        float sum3 = 0.0F;
        int index = 0;
        int upperBound = queryVector.length & ~3;
        long offset = candidateOffsetBytes;
        for (; index < upperBound; index += 4, offset += 4L * Float.BYTES) {
            sum0 += queryVector[index] * candidateSegment.get(FLOAT_LAYOUT, offset);
            sum1 += queryVector[index + 1] * candidateSegment.get(FLOAT_LAYOUT, offset + Float.BYTES);
            sum2 += queryVector[index + 2] * candidateSegment.get(FLOAT_LAYOUT, offset + 2L * Float.BYTES);
            sum3 += queryVector[index + 3] * candidateSegment.get(FLOAT_LAYOUT, offset + 3L * Float.BYTES);
        }

        float sum = (sum0 + sum1) + (sum2 + sum3);
        for (; index < queryVector.length; index++) {
            sum += queryVector[index] * candidateSegment.get(
                    FLOAT_LAYOUT,
                    offset
            );
            offset += Float.BYTES;
        }
        return sum;
    }

    private static float l2Squared(float[] left, float[] right) {
        float sum0 = 0.0F;
        float sum1 = 0.0F;
        float sum2 = 0.0F;
        float sum3 = 0.0F;
        int index = 0;
        int upperBound = left.length & ~3;
        for (; index < upperBound; index += 4) {
            float delta = left[index] - right[index];
            float delta1 = left[index + 1] - right[index + 1];
            float delta2 = left[index + 2] - right[index + 2];
            float delta3 = left[index + 3] - right[index + 3];
            sum0 += delta * delta;
            sum1 += delta1 * delta1;
            sum2 += delta2 * delta2;
            sum3 += delta3 * delta3;
        }
        float sum = (sum0 + sum1) + (sum2 + sum3);
        for (; index < left.length; index++) {
            float delta = left[index] - right[index];
            sum += delta * delta;
        }
        return sum;
    }

    private static float dot(float[] left, float[] right) {
        float sum0 = 0.0F;
        float sum1 = 0.0F;
        float sum2 = 0.0F;
        float sum3 = 0.0F;
        int index = 0;
        int upperBound = left.length & ~3;
        for (; index < upperBound; index += 4) {
            sum0 += left[index] * right[index];
            sum1 += left[index + 1] * right[index + 1];
            sum2 += left[index + 2] * right[index + 2];
            sum3 += left[index + 3] * right[index + 3];
        }
        float sum = (sum0 + sum1) + (sum2 + sum3);
        for (; index < left.length; index++) {
            sum += left[index] * right[index];
        }
        return sum;
    }
}
