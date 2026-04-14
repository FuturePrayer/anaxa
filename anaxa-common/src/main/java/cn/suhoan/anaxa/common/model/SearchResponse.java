package cn.suhoan.anaxa.common.model;

import cn.suhoan.anaxa.common.util.Copying;

import java.util.List;

public record SearchResponse(List<SearchHit> hits) {
    public SearchResponse {
        hits = Copying.immutableList(hits);
    }
}
