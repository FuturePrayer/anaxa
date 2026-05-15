package cn.suhoan.anaxa.index;

import cn.suhoan.anaxa.common.json.JsonSupport;
import org.roaringbitmap.RoaringBitmap;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class PayloadFilterIndex {
    private final int size;
    private final Map<FilterKey, RoaringBitmap> postings;

    private PayloadFilterIndex(int size, Map<FilterKey, RoaringBitmap> postings) {
        this.size = size;
        this.postings = postings;
    }

    public static PayloadFilterIndex build(List<Map<String, Object>> payloads) {
        HashMap<FilterKey, RoaringBitmap> postings = new HashMap<>();
        for (int ordinal = 0; ordinal < payloads.size(); ordinal++) {
            indexDocument(postings, payloads.get(ordinal), ordinal, payloads.size(), null);
        }
        return new PayloadFilterIndex(payloads.size(), Map.copyOf(postings));
    }

    public int size() {
        return size;
    }

    public RoaringBitmap allOrdinals() {
        RoaringBitmap all = new RoaringBitmap();
        all.add(0L, size);
        return all;
    }

    public RoaringBitmap exactMatch(String key, Object value) {
        RoaringBitmap posting = postings.get(FilterKey.of(key, value));
        return posting == null ? new RoaringBitmap() : posting.clone();
    }

    public void writeTo(DataOutput output) throws IOException {
        output.writeInt(size);
        output.writeInt(postings.size());
        for (Map.Entry<FilterKey, RoaringBitmap> entry : postings.entrySet()) {
            writeString(output, entry.getKey().key());
            writeString(output, entry.getKey().valueJson());
            byte[] bits = serializeBitmap(entry.getValue());
            output.writeInt(bits.length);
            output.write(bits);
        }
    }

    public static PayloadFilterIndex readFrom(DataInput input) throws IOException {
        int size = input.readInt();
        int postingCount = input.readInt();
        HashMap<FilterKey, RoaringBitmap> postings = new HashMap<>(postingCount);
        for (int index = 0; index < postingCount; index++) {
            String key = readString(input);
            String valueJson = readString(input);
            byte[] bits = new byte[input.readInt()];
            input.readFully(bits);
            postings.put(new FilterKey(key, valueJson), deserializeBitmap(bits));
        }
        return new PayloadFilterIndex(size, Map.copyOf(postings));
    }

    private static byte[] serializeBitmap(RoaringBitmap bitmap) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(bitmap.serializedSizeInBytes());
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            bitmap.serialize(output);
        }
        return bytes.toByteArray();
    }

    private static RoaringBitmap deserializeBitmap(byte[] bytes) throws IOException {
        RoaringBitmap bitmap = new RoaringBitmap();
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            bitmap.deserialize(input);
        }
        return bitmap;
    }

    private static void indexDocument(
            Map<FilterKey, RoaringBitmap> postings,
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

        postings.computeIfAbsent(FilterKey.of(path, value), ignored -> new RoaringBitmap()).add(ordinal);
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
