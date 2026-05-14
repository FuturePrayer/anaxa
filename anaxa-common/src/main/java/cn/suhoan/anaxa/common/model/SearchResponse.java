package cn.suhoan.anaxa.common.model;

import cn.suhoan.anaxa.common.util.Copying;

import java.util.List;

/**
 * Response body for vector search.
 *
 * @param hits ordered search hits
 */
public record SearchResponse(List<SearchHit> hits) {
    /**
     * Creates a search response.
     */
    public SearchResponse {
        hits = Copying.immutableList(hits);
    }
}
