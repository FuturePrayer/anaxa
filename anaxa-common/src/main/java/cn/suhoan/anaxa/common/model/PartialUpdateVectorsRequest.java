package cn.suhoan.anaxa.common.model;

import cn.suhoan.anaxa.common.util.Copying;

import java.util.List;
import java.util.Objects;

public record PartialUpdateVectorsRequest(List<PartialUpdateVector> updates) {
    public PartialUpdateVectorsRequest {
        updates = Copying.immutableList(Objects.requireNonNull(updates, "updates"));
        if (updates.isEmpty()) {
            throw new IllegalArgumentException("updates must not be empty");
        }
    }
}
