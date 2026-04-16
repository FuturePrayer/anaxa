package cn.suhoan.anaxa.index;

import cn.suhoan.anaxa.common.json.JsonSupport;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class PayloadFilterIndex {
    private final int size;
    private final Map<FilterKey, BitSet> postings;

    private PayloadFilterIndex(int size, Map<FilterKey, BitSet> postings) {
        this.size = size;
        this.postings = postings;
    }

    public static PayloadFilterIndex build(List<Map<String, Object>> payloads) {
        HashMap<FilterKey, BitSet> postings = new HashMap<>();
        for (int ordinal = 0; ordinal < payloads.size(); ordinal++) {
            indexDocument(postings, payloads.get(ordinal), ordinal, payloads.size(), null);
        }
        return new PayloadFilterIndex(payloads.size(), Map.copyOf(postings));
    }

    public int size() {
        return size;
    }

    public BitSet allOrdinals() {
        BitSet all = new BitSet(size);
        all.set(0, size);
        return all;
    }

    public BitSet exactMatch(String key, Object value) {
        BitSet posting = postings.get(FilterKey.of(key, value));
        return posting == null ? new BitSet(size) : (BitSet) posting.clone();
    }

    public void writeTo(DataOutput output) throws IOException {
        output.writeInt(size);
        output.writeInt(postings.size());
        for (Map.Entry<FilterKey, BitSet> entry : postings.entrySet()) {
            writeString(output, entry.getKey().key());
            writeString(output, entry.getKey().valueJson());
            byte[] bits = entry.getValue().toByteArray();
            output.writeInt(bits.length);
            output.write(bits);
        }
    }

    public static PayloadFilterIndex readFrom(DataInput input) throws IOException {
        int size = input.readInt();
        int postingCount = input.readInt();
        HashMap<FilterKey, BitSet> postings = new HashMap<>(postingCount);
        for (int index = 0; index < postingCount; index++) {
            String key = readString(input);
            String valueJson = readString(input);
            byte[] bits = new byte[input.readInt()];
            input.readFully(bits);
            postings.put(new FilterKey(key, valueJson), BitSet.valueOf(bits));
        }
        return new PayloadFilterIndex(size, Map.copyOf(postings));
    }

    private static void indexDocument(
            Map<FilterKey, BitSet> postings,
            Object value,
            int ordinal,
            int size,
            String path
    ) {
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() == null) {
                    continue;
                }
                String childPath = path == null
                        ? entry.getKey().toString()
                        : path + "." + entry.getKey();
                indexDocument(postings, entry.getValue(), ordinal, size, childPath);
            }
            return;
        }

        if (value instanceof List<?> list) {
            for (Object element : list) {
                indexDocument(postings, element, ordinal, size, path);
            }
            return;
        }

        if (path == null) {
            return;
        }

        postings.computeIfAbsent(FilterKey.of(path, value), ignored -> new BitSet(size)).set(ordinal);
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

    private record FilterKey(String key, String valueJson) {
        private static FilterKey of(String key, Object value) {
            return new FilterKey(key, JsonSupport.writeString(value));
        }
    }
}
