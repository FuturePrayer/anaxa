package cn.suhoan.anaxa.common.model;

import cn.suhoan.anaxa.common.util.Copying;

import java.util.Map;

public record SearchHit(String id, float score, Map<String, Object> payload, long sequence) {
    public SearchHit {
        payload = Copying.payload(payload);
    }
}
