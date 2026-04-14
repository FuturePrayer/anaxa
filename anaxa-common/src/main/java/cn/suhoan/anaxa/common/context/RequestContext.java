package cn.suhoan.anaxa.common.context;

import java.time.Instant;
import java.util.Objects;

public record RequestContext(String traceId, Instant startedAt) {
    public RequestContext {
        traceId = Objects.requireNonNull(traceId, "traceId");
        startedAt = Objects.requireNonNull(startedAt, "startedAt");
    }
}
