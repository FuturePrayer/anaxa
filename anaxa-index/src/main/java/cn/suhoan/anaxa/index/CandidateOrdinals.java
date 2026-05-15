package cn.suhoan.anaxa.index;

import org.roaringbitmap.RoaringBitmap;

import java.util.BitSet;

final class CandidateOrdinals {
    private final RoaringBitmap ordinals;
    private BitSet bitSet;

    private CandidateOrdinals(RoaringBitmap ordinals) {
        this.ordinals = ordinals;
    }

    static CandidateOrdinals of(RoaringBitmap ordinals) {
        return new CandidateOrdinals(ordinals.clone());
    }

    static CandidateOrdinals all(int size) {
        RoaringBitmap ordinals = new RoaringBitmap();
        ordinals.add(0L, size);
        return new CandidateOrdinals(ordinals);
    }

    boolean isEmpty() {
        return ordinals.isEmpty();
    }

    int cardinality() {
        return ordinals.getCardinality();
    }

    boolean contains(int ordinal) {
        return ordinals.contains(ordinal);
    }

    int nextSetBit(int fromIndex) {
        long next = ordinals.nextValue(fromIndex);
        return next > Integer.MAX_VALUE ? -1 : (int) next;
    }

    BitSet bitSet() {
        BitSet current = bitSet;
        if (current != null) {
            return current;
        }
        BitSet converted = new BitSet();
        for (int ordinal : ordinals) {
            converted.set(ordinal);
        }
        bitSet = converted;
        return converted;
    }
}
