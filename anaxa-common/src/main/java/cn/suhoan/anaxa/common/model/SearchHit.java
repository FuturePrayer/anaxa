package cn.suhoan.anaxa.common.model;

import cn.suhoan.anaxa.common.util.Copying;

import java.util.Map;

/**
 * Single vector hit returned by a search request.
 *
 * @param id vector id
 * @param score similarity or distance score
 * @param payload vector payload
 * @param sequence storage sequence number
 */
public record SearchHit(String id, float score, Map<String, Object> payload, long sequence) {
    /**
     * Creates a search hit.
     */
    public SearchHit {
        payload = Copying.payload(payload);
    }
}
