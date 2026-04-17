package cn.suhoan.anaxa.common.model;

import cn.suhoan.anaxa.common.util.Copying;

import java.util.Map;
import java.util.Objects;

public record PartialUpdateVector(String id, Map<String, Object> payload) {
    public PartialUpdateVector {
        id = Objects.requireNonNull(id, "id").trim();
        if (id.isEmpty()) {
            throw new IllegalArgumentException("Vector id must not be blank");
        }
        payload = Copying.payload(payload);
        if (payload.isEmpty()) {
            throw new IllegalArgumentException("payload must not be empty");
        }
    }
}
