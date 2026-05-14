package cn.suhoan.anaxa.common.model;

import cn.suhoan.anaxa.common.util.Copying;

import java.util.List;
import java.util.Objects;

/**
 * Request body for applying payload-only vector updates.
 *
 * @param updates vector updates to apply
 */
public record PartialUpdateVectorsRequest(List<PartialUpdateVector> updates) {
    /**
     * Creates a partial-update request.
     */
    public PartialUpdateVectorsRequest {
        updates = Copying.immutableList(Objects.requireNonNull(updates, "updates"));
        if (updates.isEmpty()) {
            throw new IllegalArgumentException("updates must not be empty");
        }
    }
}
