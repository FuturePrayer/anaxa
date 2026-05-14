package cn.suhoan.anaxa.common.model;

/**
 * Error response returned by HTTP APIs.
 *
 * @param message human-readable error message
 * @param traceId request trace identifier, when available
 */
public record ErrorResponse(String message, String traceId) {
}
