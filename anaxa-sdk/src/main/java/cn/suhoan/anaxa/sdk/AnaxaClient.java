package cn.suhoan.anaxa.sdk;

import cn.suhoan.anaxa.common.json.JsonSupport;
import cn.suhoan.anaxa.common.model.BackupCollectionRequest;
import cn.suhoan.anaxa.common.model.BackupSummary;
import cn.suhoan.anaxa.common.model.CollectionDefinition;
import cn.suhoan.anaxa.common.model.CollectionStats;
import cn.suhoan.anaxa.common.model.CreateCollectionRequest;
import cn.suhoan.anaxa.common.model.ErrorResponse;
import cn.suhoan.anaxa.common.model.HealthResponse;
import cn.suhoan.anaxa.common.model.TenantSnapshotResult;
import cn.suhoan.anaxa.common.model.TenantStats;
import cn.suhoan.anaxa.common.model.UpsertVector;
import cn.suhoan.anaxa.common.util.BinaryVectorStreams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.type.TypeReference;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * AnaxaDB Java SDK 的入口。
 *
 * <p>这个类负责三件事：
 *
 * <ol>
 *   <li>维护共享的 {@link HttpClient} 和认证/tenant 等默认请求参数。</li>
 *   <li>提供与服务端 HTTP 契约一一对应的基础 API。</li>
 *   <li>为 {@link AnaxaCollectionClient} 提供公共的 HTTP 发送与错误处理能力。</li>
 * </ol>
 *
 * <p>设计上保持“轻对象、可复制”：通过 {@link #withTenant(String)} 或
 * {@link #withApiKey(String)} 派生出来的新实例会复用底层 {@link HttpClient}，
 * 不会重复创建连接池。
 */
public final class AnaxaClient implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(AnaxaClient.class);
    private static final TypeReference<List<CollectionStats>> COLLECTION_STATS_LIST = new TypeReference<>() {
    };
    private static final TypeReference<List<TenantStats>> TENANT_STATS_LIST = new TypeReference<>() {
    };
    private static final TypeReference<List<BackupSummary>> BACKUP_SUMMARY_LIST = new TypeReference<>() {
    };

    private final HttpClient httpClient;
    private final URI baseUri;
    private final String apiKey;
    private final String defaultTenantId;
    private final Duration requestTimeout;
    private final Map<String, String> defaultHeaders;

    private AnaxaClient(
            HttpClient httpClient,
            URI baseUri,
            String apiKey,
            String defaultTenantId,
            Duration requestTimeout,
            Map<String, String> defaultHeaders
    ) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.baseUri = validateBaseUri(baseUri);
        this.apiKey = normalizeOptional(apiKey);
        this.defaultTenantId = normalizeTenantId(defaultTenantId);
        this.requestTimeout = requirePositive(requestTimeout, "requestTimeout");
        this.defaultHeaders = Map.copyOf(Objects.requireNonNull(defaultHeaders, "defaultHeaders"));
    }

    /**
     * 创建一个新的 Builder。
     *
     * @return SDK builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 基于当前客户端派生一个新的 tenant 视图。
     *
     * <p>常见用途是：全局 admin key 先创建一个无 tenant 作用域的客户端，
     * 然后在实际调用前通过这个方法切换到某个具体 tenant。
     *
     * @param tenantId tenant id to bind, or null to clear it
     * @return derived client bound to the tenant
     */
    public AnaxaClient withTenant(String tenantId) {
        return new AnaxaClient(httpClient, baseUri, apiKey, tenantId, requestTimeout, defaultHeaders);
    }

    /**
     * 返回一个不携带默认 tenant 头的新客户端。
     *
     * @return derived client without a default tenant
     */
    public AnaxaClient withoutTenant() {
        return withTenant(null);
    }

    /**
     * 基于当前配置派生一个新的 API Key。
     *
     * @param newApiKey API key to bind, or null to clear it
     * @return derived client bound to the API key
     */
    public AnaxaClient withApiKey(String newApiKey) {
        return new AnaxaClient(httpClient, baseUri, newApiKey, defaultTenantId, requestTimeout, defaultHeaders);
    }

    /**
     * 获取某个 collection 的绑定客户端。
     *
     * @param collectionName collection name
     * @return collection-scoped client
     */
    public AnaxaCollectionClient collection(String collectionName) {
        return new AnaxaCollectionClient(this, collectionName);
    }

    /**
     * 调用 {@code GET /health}。
     *
     * @return health response
     * @throws IOException when the HTTP request fails
     * @throws InterruptedException when the request thread is interrupted
     */
    public HealthResponse health() throws IOException, InterruptedException {
        return sendJson("GET", path("health"), null, HealthResponse.class);
    }

    /**
     * 调用 {@code GET /metrics}，返回 Prometheus 文本格式。
     *
     * @return Prometheus metrics text
     * @throws IOException when the HTTP request fails
     * @throws InterruptedException when the request thread is interrupted
     */
    public String metrics() throws IOException, InterruptedException {
        return sendText("GET", path("metrics"));
    }

    /**
     * 列出当前 tenant 作用域下可见的 collection。
     *
     * @return visible collections
     * @throws IOException when the HTTP request fails
     * @throws InterruptedException when the request thread is interrupted
     */
    public List<CollectionStats> listCollections() throws IOException, InterruptedException {
        return sendJson("GET", path("collections"), null, COLLECTION_STATS_LIST);
    }

    /**
     * 创建 collection。
     *
     * @param request create-collection request
     * @return created collection statistics
     * @throws IOException when the HTTP request fails
     * @throws InterruptedException when the request thread is interrupted
     */
    public CollectionStats createCollection(CreateCollectionRequest request) throws IOException, InterruptedException {
        return sendJson("POST", path("collections"), Objects.requireNonNull(request, "request"), CollectionStats.class);
    }

    /**
     * 查询指定 collection 的统计信息。
     *
     * @param collectionName collection name
     * @return collection statistics
     * @throws IOException when the HTTP request fails
     * @throws InterruptedException when the request thread is interrupted
     */
    public CollectionStats getCollection(String collectionName) throws IOException, InterruptedException {
        return sendJson("GET", path("collections", collectionName), null, CollectionStats.class);
    }

    /**
     * 列出当前 tenant 或全局视图下的 tenant 运维信息。
     *
     * @return tenant statistics
     * @throws IOException when the HTTP request fails
     * @throws InterruptedException when the request thread is interrupted
     */
    public List<TenantStats> listTenants() throws IOException, InterruptedException {
        return sendJson("GET", path("tenants"), null, TENANT_STATS_LIST);
    }

    /**
     * 查询单个 tenant 的运维信息。
     *
     * @param tenantId tenant id
     * @return tenant statistics
     * @throws IOException when the HTTP request fails
     * @throws InterruptedException when the request thread is interrupted
     */
    public TenantStats getTenant(String tenantId) throws IOException, InterruptedException {
        return sendJson("GET", path("tenants", tenantId), null, TenantStats.class);
    }

    /**
     * 查看当前 tenant 或全局视图下的备份列表。
     *
     * @return backup summaries
     * @throws IOException when the HTTP request fails
     * @throws InterruptedException when the request thread is interrupted
     */
    public List<BackupSummary> listBackups() throws IOException, InterruptedException {
        return sendJson("GET", path("backups"), null, BACKUP_SUMMARY_LIST);
    }

    /**
     * 对整个 tenant 执行一次手工 snapshot，并由服务端生成 backupId。
     *
     * @param tenantId tenant id
     * @return snapshot result
     * @throws IOException when the HTTP request fails
     * @throws InterruptedException when the request thread is interrupted
     */
    public TenantSnapshotResult snapshotTenant(String tenantId) throws IOException, InterruptedException {
        return sendJson("POST", path("tenants", tenantId, "snapshot"), null, TenantSnapshotResult.class);
    }

    /**
     * 对整个 tenant 执行一次手工 snapshot，并显式指定 backupId。
     *
     * @param tenantId tenant id
     * @param backupId backup id to create
     * @return snapshot result
     * @throws IOException when the HTTP request fails
     * @throws InterruptedException when the request thread is interrupted
     */
    public TenantSnapshotResult snapshotTenant(String tenantId, String backupId) throws IOException, InterruptedException {
        return sendJson(
                "POST",
                path("tenants", tenantId, "snapshot"),
                new BackupCollectionRequest(backupId),
                TenantSnapshotResult.class
        );
    }

    /**
     * 当前客户端绑定的默认 tenant。
     *
     * @return default tenant id, when configured
     */
    public Optional<String> defaultTenantId() {
        return Optional.ofNullable(defaultTenantId);
    }

    /**
     * 当前客户端绑定的基础地址。
     *
     * @return server base URI
     */
    public URI baseUri() {
        return baseUri;
    }

    /**
     * 当前客户端复用的底层 {@link HttpClient}。
     *
     * @return underlying HTTP client
     */
    public HttpClient httpClient() {
        return httpClient;
    }

    /**
     * 对于 JDK 自带 {@link HttpClient} 来说，当前 SDK 没有需要显式释放的资源。
     *
     * <p>保留 {@link AutoCloseable} 接口，是为了让业务方可以习惯性地用
     * try-with-resources 包裹客户端实例，而不需要额外记忆这个 SDK 的特殊性。
     */
    @Override
    public void close() {
        // no-op
    }

    CollectionStats sendBinaryUpsert(String collectionName, int dimension, Iterable<UpsertVector> vectors)
            throws IOException, InterruptedException {
        byte[] body = BinaryVectorStreams.writeBatch(dimension, vectors);
        return sendBytes(
                "POST",
                path("collections", collectionName, "vectors"),
                "application/vnd.anaxa.vector-batch",
                body,
                CollectionStats.class
        );
    }

    CollectionStats sendNdjsonUpsert(String collectionName, Iterable<UpsertVector> vectors)
            throws IOException, InterruptedException {
        return sendBytes(
                "POST",
                path("collections", collectionName, "vectors"),
                "application/x-ndjson",
                toNdjsonBytes(vectors),
                CollectionStats.class
        );
    }

    <T> T sendJson(String method, String path, Object requestBody, Class<T> responseType)
            throws IOException, InterruptedException {
        Objects.requireNonNull(responseType, "responseType");
        return send(method, path, "application/json", requestBody == null ? null : JsonSupport.writeBytes(requestBody), body -> {
            if (body.length == 0) {
                throw new IllegalStateException("Expected JSON response body but got empty body");
            }
            return JsonSupport.read(body, responseType);
        });
    }

    <T> T sendJson(String method, String path, Object requestBody, TypeReference<T> responseType)
            throws IOException, InterruptedException {
        Objects.requireNonNull(responseType, "responseType");
        return send(method, path, "application/json", requestBody == null ? null : JsonSupport.writeBytes(requestBody), body -> {
            if (body.length == 0) {
                throw new IllegalStateException("Expected JSON response body but got empty body");
            }
            try {
                return JsonSupport.mapper().readValue(body, responseType);
            } catch (Exception exception) {
                throw new IllegalArgumentException("Failed to deserialize JSON response body", exception);
            }
        });
    }

    <T> T sendBytes(String method, String path, String contentType, byte[] requestBody, Class<T> responseType)
            throws IOException, InterruptedException {
        Objects.requireNonNull(responseType, "responseType");
        return send(method, path, contentType, requestBody, body -> {
            if (body.length == 0) {
                throw new IllegalStateException("Expected JSON response body but got empty body");
            }
            return JsonSupport.read(body, responseType);
        });
    }

    String sendText(String method, String path) throws IOException, InterruptedException {
        return send(method, path, null, null, body -> new String(body, StandardCharsets.UTF_8));
    }

    String path(String... segments) {
        StringBuilder builder = new StringBuilder();
        for (String segment : segments) {
            if (segment == null) {
                throw new IllegalArgumentException("Path segment must not be null");
            }
            String normalized = segment.trim();
            if (normalized.isEmpty()) {
                throw new IllegalArgumentException("Path segment must not be blank");
            }
            builder.append('/').append(encodePathSegment(normalized));
        }
        return builder.toString();
    }

    private <T> T send(String method, String path, String contentType, byte[] requestBody, ResponseDecoder<T> decoder)
            throws IOException, InterruptedException {
        HttpRequest request = buildRequest(method, path, contentType, requestBody);
        long startedAtNanos = System.nanoTime();
        log.debug("开始发送请求: method={} uri={}", request.method(), request.uri());
        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - startedAtNanos).toMillis();
        log.debug(
                "请求完成: method={} uri={} status={} elapsedMs={}",
                request.method(),
                request.uri(),
                response.statusCode(),
                elapsedMillis
        );
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw toClientException(request, response);
        }
        return decoder.decode(response.body() == null ? new byte[0] : response.body());
    }

    private HttpRequest buildRequest(String method, String path, String contentType, byte[] requestBody) {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(path, "path");

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(resolveUri(path))
                .timeout(requestTimeout);
        if (contentType != null) {
            builder.header("Content-Type", contentType + "; charset=utf-8");
        }
        builder.header("Accept", "application/json, text/plain;q=0.9, */*;q=0.8");

        if (apiKey != null) {
            builder.header("X-API-Key", apiKey);
        }
        if (defaultTenantId != null) {
            builder.header("X-Tenant-Id", defaultTenantId);
        }
        defaultHeaders.forEach(builder::header);

        HttpRequest.BodyPublisher bodyPublisher = requestBody == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(requestBody);
        builder.method(method, bodyPublisher);
        return builder.build();
    }

    private URI resolveUri(String path) {
        String base = baseUri.toString();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return URI.create(base + (path.startsWith("/") ? path : "/" + path));
    }

    private AnaxaClientException toClientException(HttpRequest request, HttpResponse<byte[]> response) {
        byte[] body = response.body() == null ? new byte[0] : response.body();
        String rawBody = new String(body, StandardCharsets.UTF_8);
        String traceId = response.headers().firstValue("X-Trace-Id").orElse(null);
        String message = rawBody.isBlank()
                ? "Request failed with HTTP " + response.statusCode()
                : rawBody;
        if (!rawBody.isBlank()) {
            try {
                ErrorResponse error = JsonSupport.mapper().readValue(body, ErrorResponse.class);
                message = error.message() == null || error.message().isBlank() ? message : error.message();
                if (traceId == null || traceId.isBlank()) {
                    traceId = error.traceId();
                }
            } catch (Exception ignored) {
                // 响应不一定总是标准 JSON；保留原始文本即可。
            }
        }
        String exceptionMessage = "Anaxa request failed: method=%s uri=%s status=%d message=%s"
                .formatted(request.method(), request.uri(), response.statusCode(), message);
        return new AnaxaClientException(
                exceptionMessage,
                response.statusCode(),
                traceId,
                rawBody,
                request.method(),
                request.uri()
        );
    }

    private static byte[] toNdjsonBytes(Iterable<UpsertVector> vectors) {
        StringBuilder builder = new StringBuilder();
        long count = 0L;
        for (UpsertVector vector : vectors) {
            builder.append(JsonSupport.writeString(vector)).append('\n');
            count++;
        }
        if (count == 0L) {
            throw new IllegalArgumentException("vectors must not be empty");
        }
        return builder.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static URI validateBaseUri(URI baseUri) {
        Objects.requireNonNull(baseUri, "baseUri");
        if (baseUri.getScheme() == null || baseUri.getScheme().isBlank()) {
            throw new IllegalArgumentException("baseUri must include scheme");
        }
        if (baseUri.getHost() == null || baseUri.getHost().isBlank()) {
            throw new IllegalArgumentException("baseUri must include host");
        }
        return baseUri;
    }

    private static Duration requirePositive(Duration duration, String fieldName) {
        Objects.requireNonNull(duration, fieldName);
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(fieldName + " must be positive");
        }
        return duration;
    }

    private static String normalizeOptional(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private static String normalizeTenantId(String tenantId) {
        String normalized = normalizeOptional(tenantId);
        return normalized == null ? null : CollectionDefinition.normalizeTenantId(normalized);
    }

    private static String encodePathSegment(String segment) {
        return URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /**
     * SDK Builder。
     */
    public static final class Builder {
        private URI baseUri;
        private String apiKey;
        private String defaultTenantId;
        private Duration requestTimeout = Duration.ofSeconds(30);
        private Duration connectTimeout = Duration.ofSeconds(10);
        private HttpClient httpClient;
        private final Map<String, String> defaultHeaders = new LinkedHashMap<>();

        private Builder() {
        }

        /**
         * 设置服务端基础地址，例如 {@code http://127.0.0.1:30720}。
         *
         * @param newBaseUri server base URI string
         * @return this builder
         */
        public Builder baseUri(String newBaseUri) {
            return baseUri(URI.create(Objects.requireNonNull(newBaseUri, "newBaseUri")));
        }

        /**
         * 设置服务端基础地址。
         *
         * @param newBaseUri server base URI
         * @return this builder
         */
        public Builder baseUri(URI newBaseUri) {
            this.baseUri = newBaseUri;
            return this;
        }

        /**
         * 设置默认 API Key。
         *
         * <p>如果服务端运行在 open mode，这个字段可以不传。
         *
         * @param newApiKey default API key
         * @return this builder
         */
        public Builder apiKey(String newApiKey) {
            this.apiKey = newApiKey;
            return this;
        }

        /**
         * 设置默认 tenant。
         *
         * @param tenantId default tenant id
         * @return this builder
         */
        public Builder defaultTenantId(String tenantId) {
            this.defaultTenantId = tenantId;
            return this;
        }

        /**
         * 设置每个请求的默认超时时间。
         *
         * @param timeout request timeout
         * @return this builder
         */
        public Builder requestTimeout(Duration timeout) {
            this.requestTimeout = timeout;
            return this;
        }

        /**
         * 设置底层 {@link HttpClient} 的连接超时。
         *
         * <p>如果调用方显式传入了自定义 {@link #httpClient(HttpClient)}，
         * 这里的值不会再参与构建。
         *
         * @param timeout connection timeout
         * @return this builder
         */
        public Builder connectTimeout(Duration timeout) {
            this.connectTimeout = timeout;
            return this;
        }

        /**
         * 允许业务方自行传入一个已经配置好的 {@link HttpClient}。
         *
         * @param newHttpClient HTTP client to reuse
         * @return this builder
         */
        public Builder httpClient(HttpClient newHttpClient) {
            this.httpClient = newHttpClient;
            return this;
        }

        /**
         * 添加一个所有请求都会透传的自定义请求头。
         *
         * @param name header name
         * @param value header value
         * @return this builder
         */
        public Builder defaultHeader(String name, String value) {
            String normalizedName = Objects.requireNonNull(name, "name").trim();
            String normalizedValue = Objects.requireNonNull(value, "value").trim();
            if (normalizedName.isEmpty()) {
                throw new IllegalArgumentException("Header name must not be blank");
            }
            if (normalizedValue.isEmpty()) {
                throw new IllegalArgumentException("Header value must not be blank");
            }
            defaultHeaders.put(normalizedName, normalizedValue);
            return this;
        }

        /**
         * 构建客户端。
         *
         * @return configured SDK client
         */
        public AnaxaClient build() {
            HttpClient resolvedHttpClient = httpClient;
            if (resolvedHttpClient == null) {
                resolvedHttpClient = HttpClient.newBuilder()
                        .connectTimeout(requirePositive(connectTimeout, "connectTimeout"))
                        .version(HttpClient.Version.HTTP_1_1)
                        .build();
            }
            return new AnaxaClient(
                    resolvedHttpClient,
                    baseUri,
                    apiKey,
                    defaultTenantId,
                    requestTimeout,
                    defaultHeaders
            );
        }
    }

    @FunctionalInterface
    private interface ResponseDecoder<T> {
        T decode(byte[] body) throws IOException;
    }
}
