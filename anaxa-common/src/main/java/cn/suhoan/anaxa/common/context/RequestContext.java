package cn.suhoan.anaxa.common.context;

import java.time.Instant;
import java.util.Objects;

/**
 * Request-scoped metadata shared by server-side operations.
 *
 * @param traceId unique trace identifier for the request
 * @param startedAt time when request processing started
 */
public record RequestContext(String traceId, Instant startedAt) {
    /**
     * Creates a request context.
     */
    public RequestContext {
        traceId = Objects.requireNonNull(traceId, "traceId");
        startedAt = Objects.requireNonNull(startedAt, "startedAt");
    }
}
