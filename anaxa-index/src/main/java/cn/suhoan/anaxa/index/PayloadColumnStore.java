package cn.suhoan.anaxa.index;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class PayloadColumnStore {
    private final int size;
    private final Map<String, NumericColumn> numericColumns;
    private final Map<String, StringColumn> stringColumns;

    private PayloadColumnStore(
            int size,
            Map<String, NumericColumn> numericColumns,
            Map<String, StringColumn> stringColumns
    ) {
        this.size = size;
        this.numericColumns = numericColumns;
        this.stringColumns = stringColumns;
    }

    static PayloadColumnStore build(List<Map<String, Object>> payloads) {
        HashMap<String, NumericColumnBuilder> numericBuilders = new HashMap<>();
        HashMap<String, StringColumnBuilder> stringBuilders = new HashMap<>();
        for (int ordinal = 0; ordinal < payloads.size(); ordinal++) {
            indexValue(payloads.get(ordinal), ordinal, payloads.size(), null, numericBuilders, stringBuilders);
        }

        HashMap<String, NumericColumn> numericColumns = new HashMap<>();
        for (Map.Entry<String, NumericColumnBuilder> entry : numericBuilders.entrySet()) {
            numericColumns.put(entry.getKey(), entry.getValue().build());
        }

        HashMap<String, StringColumn> stringColumns = new HashMap<>();
        for (Map.Entry<String, StringColumnBuilder> entry : stringBuilders.entrySet()) {
            stringColumns.put(entry.getKey(), entry.getValue().build());
        }
        return new PayloadColumnStore(payloads.size(), Map.copyOf(numericColumns), Map.copyOf(stringColumns));
    }

    int size() {
        return size;
    }

    BitSet rangeMatch(String field, Object lowerBound, boolean includeLowerBound, Object upperBound, boolean includeUpperBound) {
        if ((lowerBound instanceof Number || upperBound instanceof Number) && numericColumns.containsKey(field)) {
            return numericColumns.get(field).match(lowerBound, includeLowerBound, upperBound, includeUpperBound);
        }
        if ((lowerBound instanceof String || upperBound instanceof String) && stringColumns.containsKey(field)) {
            return stringColumns.get(field).match(lowerBound, includeLowerBound, upperBound, includeUpperBound);
        }
        return null;
    }

    void writeTo(DataOutput output) throws IOException {
        output.writeInt(size);
        output.writeInt(numericColumns.size());
        for (Map.Entry<String, NumericColumn> entry : numericColumns.entrySet()) {
            writeString(output, entry.getKey());
            entry.getValue().writeTo(output);
        }
        output.writeInt(stringColumns.size());
        for (Map.Entry<String, StringColumn> entry : stringColumns.entrySet()) {
            writeString(output, entry.getKey());
            entry.getValue().writeTo(output);
        }
    }

    static PayloadColumnStore readFrom(DataInput input) throws IOException {
        int size = input.readInt();
        int numericColumnCount = input.readInt();
        HashMap<String, NumericColumn> numericColumns = new HashMap<>(numericColumnCount);
        for (int index = 0; index < numericColumnCount; index++) {
            numericColumns.put(readString(input), NumericColumn.readFrom(input, size));
        }

        int stringColumnCount = input.readInt();
        HashMap<String, StringColumn> stringColumns = new HashMap<>(stringColumnCount);
        for (int index = 0; index < stringColumnCount; index++) {
            stringColumns.put(readString(input), StringColumn.readFrom(input, size));
        }
        return new PayloadColumnStore(size, Map.copyOf(numericColumns), Map.copyOf(stringColumns));
    }

    private static void indexValue(
            Object value,
            int ordinal,
            int size,
            String path,
            Map<String, NumericColumnBuilder> numericBuilders,
            Map<String, StringColumnBuilder> stringBuilders
    ) {
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() == null) {
                    continue;
                }
                String childPath = path == null ? entry.getKey().toString() : path + "." + entry.getKey();
                indexValue(entry.getValue(), ordinal, size, childPath, numericBuilders, stringBuilders);
            }
            return;
        }
        if (value instanceof List<?>) {
            return;
        }
        if (path == null) {
            return;
        }
        if (value instanceof Number number) {
            numericBuilders.computeIfAbsent(path, ignored -> new NumericColumnBuilder(size)).set(ordinal, number.doubleValue());
        } else if (value instanceof String text) {
            stringBuilders.computeIfAbsent(path, ignored -> new StringColumnBuilder(size)).set(ordinal, text);
        }
    }

    private static void writeString(DataOutput output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static String readString(DataInput input) throws IOException {
        byte[] bytes = new byte[input.readInt()];
        input.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static final class NumericColumnBuilder {
        private final double[] values;
        private final BitSet present;

        private NumericColumnBuilder(int size) {
            this.values = new double[size];
            this.present = new BitSet(size);
        }

        private void set(int ordinal, double value) {
            values[ordinal] = value;
            present.set(ordinal);
        }

        private NumericColumn build() {
            return new NumericColumn(values, (BitSet) present.clone());
        }
    }

    private static final class StringColumnBuilder {
        private final String[] values;
        private final BitSet present;

        private StringColumnBuilder(int size) {
            this.values = new String[size];
            this.present = new BitSet(size);
        }

        private void set(int ordinal, String value) {
            values[ordinal] = value;
            present.set(ordinal);
        }

        private StringColumn build() {
            return new StringColumn(values, (BitSet) present.clone());
        }
    }

    private record NumericColumn(double[] values, BitSet present) {
        private BitSet match(Object lowerBound, boolean includeLowerBound, Object upperBound, boolean includeUpperBound) {
            BitSet matches = new BitSet(values.length);
            for (int ordinal = present.nextSetBit(0); ordinal >= 0; ordinal = present.nextSetBit(ordinal + 1)) {
                double value = values[ordinal];
                if (lowerBound instanceof Number lower) {
                    int comparison = Double.compare(value, lower.doubleValue());
                    if (comparison < 0 || (!includeLowerBound && comparison == 0)) {
                        continue;
                    }
                }
                if (upperBound instanceof Number upper) {
                    int comparison = Double.compare(value, upper.doubleValue());
                    if (comparison > 0 || (!includeUpperBound && comparison == 0)) {
                        continue;
                    }
                }
                matches.set(ordinal);
            }
            return matches;
        }

        private void writeTo(DataOutput output) throws IOException {
            byte[] presentBits = present.toByteArray();
            output.writeInt(presentBits.length);
            output.write(presentBits);
            for (double value : values) {
                output.writeDouble(value);
            }
        }

        private static NumericColumn readFrom(DataInput input, int size) throws IOException {
            byte[] bits = new byte[input.readInt()];
            input.readFully(bits);
            double[] values = new double[size];
            for (int index = 0; index < size; index++) {
                values[index] = input.readDouble();
            }
            return new NumericColumn(values, BitSet.valueOf(bits));
        }
    }

    private record StringColumn(String[] values, BitSet present) {
        private BitSet match(Object lowerBound, boolean includeLowerBound, Object upperBound, boolean includeUpperBound) {
            BitSet matches = new BitSet(values.length);
            for (int ordinal = present.nextSetBit(0); ordinal >= 0; ordinal = present.nextSetBit(ordinal + 1)) {
                String value = values[ordinal];
                if (lowerBound instanceof String lower) {
                    int comparison = value.compareTo(lower);
                    if (comparison < 0 || (!includeLowerBound && comparison == 0)) {
                        continue;
                    }
                }
                if (upperBound instanceof String upper) {
                    int comparison = value.compareTo(upper);
                    if (comparison > 0 || (!includeUpperBound && comparison == 0)) {
                        continue;
                    }
                }
                matches.set(ordinal);
            }
            return matches;
        }

        private void writeTo(DataOutput output) throws IOException {
            byte[] presentBits = present.toByteArray();
            output.writeInt(presentBits.length);
            output.write(presentBits);
            ArrayList<String> storedValues = new ArrayList<>(present.cardinality());
            for (int ordinal = present.nextSetBit(0); ordinal >= 0; ordinal = present.nextSetBit(ordinal + 1)) {
                storedValues.add(values[ordinal]);
            }
            output.writeInt(storedValues.size());
            for (String value : storedValues) {
                writeString(output, value);
            }
        }

        private static StringColumn readFrom(DataInput input, int size) throws IOException {
            byte[] bits = new byte[input.readInt()];
            input.readFully(bits);
            BitSet present = BitSet.valueOf(bits);
            String[] values = new String[size];
            int valueCount = input.readInt();
            int ordinal = present.nextSetBit(0);
            for (int index = 0; index < valueCount && ordinal >= 0; index++) {
                values[ordinal] = readString(input);
                ordinal = present.nextSetBit(ordinal + 1);
            }
            return new StringColumn(values, present);
        }
    }
}
