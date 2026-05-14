package cn.suhoan.anaxa.common.model;

/**
 * Health response returned by the server.
 *
 * @param status health status string
 */
public record HealthResponse(String status) {
}
