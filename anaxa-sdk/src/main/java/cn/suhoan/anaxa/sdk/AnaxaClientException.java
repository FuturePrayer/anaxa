package cn.suhoan.anaxa.sdk;

import java.io.IOException;
import java.net.URI;
import java.util.Objects;

/**
 * 当服务端返回非 2xx 状态码时抛出的异常。
 *
 * <p>相比只暴露一个字符串消息，这个异常会把调用定位所需的上下文一并保留下来，
 * 例如 HTTP 状态码、服务端返回的 traceId、原始响应体、请求方法和目标 URI。
 * 这样业务方在日志、监控或者重试逻辑里都能拿到足够的信息。
 */
public final class AnaxaClientException extends IOException {
    private final int statusCode;
    private final String traceId;
    private final String responseBody;
    private final String httpMethod;
    private final URI requestUri;

    public AnaxaClientException(
            String message,
            int statusCode,
            String traceId,
            String responseBody,
            String httpMethod,
            URI requestUri
    ) {
        super(Objects.requireNonNull(message, "message"));
        this.statusCode = statusCode;
        this.traceId = traceId;
        this.responseBody = responseBody == null ? "" : responseBody;
        this.httpMethod = Objects.requireNonNull(httpMethod, "httpMethod");
        this.requestUri = Objects.requireNonNull(requestUri, "requestUri");
    }

    /**
     * 服务端返回的 HTTP 状态码。
     */
    public int statusCode() {
        return statusCode;
    }

    /**
     * 服务端透传的 traceId。
     *
     * <p>当调用链需要串联 SDK 日志与服务端日志时，这个字段非常有用。
     */
    public String traceId() {
        return traceId;
    }

    /**
     * 原始响应体。
     *
     * <p>如果服务端返回的是标准的 {@code ErrorResponse}，这里通常会是一段 JSON 文本。
     */
    public String responseBody() {
        return responseBody;
    }

    /**
     * 触发异常的 HTTP 方法，例如 {@code GET}、{@code POST}。
     */
    public String httpMethod() {
        return httpMethod;
    }

    /**
     * 触发异常的完整请求地址。
     */
    public URI requestUri() {
        return requestUri;
    }
}
