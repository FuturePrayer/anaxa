package cn.suhoan.anaxa.common.model;

import java.util.List;
import java.util.Objects;

public record DeleteVectorsRequest(List<String> ids) {
    public DeleteVectorsRequest {
        ids = List.copyOf(Objects.requireNonNull(ids, "ids").stream()
                .map(id -> Objects.requireNonNull(id, "id").trim())
                .toList());
        if (ids.isEmpty()) {
            throw new IllegalArgumentException("ids must not be empty");
        }
        for (String id : ids) {
            if (id.isEmpty()) {
                throw new IllegalArgumentException("Vector id must not be blank");
            }
        }
    }
}
