package cn.suhoan.anaxa.server;

import cn.suhoan.anaxa.common.context.RequestContext;
import cn.suhoan.anaxa.common.error.NotFoundException;
import cn.suhoan.anaxa.common.error.ValidationException;
import cn.suhoan.anaxa.common.json.JsonSupport;
import cn.suhoan.anaxa.common.model.BackupCollectionRequest;
import cn.suhoan.anaxa.common.model.CollectionDefinition;
import cn.suhoan.anaxa.common.model.CollectionStats;
import cn.suhoan.anaxa.common.model.CreateCollectionRequest;
import cn.suhoan.anaxa.common.model.DeleteVectorsRequest;
import cn.suhoan.anaxa.common.model.ErrorResponse;
import cn.suhoan.anaxa.common.model.HealthResponse;
import cn.suhoan.anaxa.common.model.RestoreCollectionRequest;
import cn.suhoan.anaxa.common.model.SearchRequest;
import cn.suhoan.anaxa.common.model.SearchResponse;
import cn.suhoan.anaxa.common.model.UpsertVectorsRequest;
import cn.suhoan.anaxa.engine.VectorDatabaseEngine;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class AnaxaHttpServer implements AutoCloseable {
    private static final ScopedValue<RequestContext> CURRENT_REQUEST = ScopedValue.newInstance();

    private final VectorDatabaseEngine engine;
    private final HttpServer server;
    private final ExecutorService requestExecutor;
    private final ApiKeyAuthorizer authorizer;
    private final RequestRateLimiter rateLimiter;
    private final MetricsRegistry metrics;
    private final AuditLogger auditLogger;
    private final ServerConfig config;

    public AnaxaHttpServer(ServerConfig config) throws IOException {
        this(new MetricsRegistry(), config);
    }

    private AnaxaHttpServer(MetricsRegistry metrics, ServerConfig config) throws IOException {
        this(new VectorDatabaseEngine(config.dataDirectory(), config.defaultFlushThresholdBytes(), metrics), config, metrics);
    }

    AnaxaHttpServer(VectorDatabaseEngine engine, ServerConfig config) throws IOException {
        this(engine, config, new MetricsRegistry());
    }

    private AnaxaHttpServer(VectorDatabaseEngine engine, ServerConfig config, MetricsRegistry metrics) throws IOException {
        this.engine = engine;
        this.config = config;
        this.server = HttpServer.create(new InetSocketAddress(config.host(), config.port()), 0);
        this.requestExecutor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("anaxa-http-", 0).factory());
        this.authorizer = new ApiKeyAuthorizer(config.apiKeys(), config.apiKeyFile());
        this.rateLimiter = new RequestRateLimiter(config.rateLimitPerMinute(), config.rateLimitBurst());
        this.metrics = metrics;
        this.auditLogger = new AuditLogger(config.auditLogPath());
        this.server.setExecutor(requestExecutor);
        this.server.createContext("/", this::handleExchange);
    }

    public void start() {
        server.start();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(1);
        requestExecutor.shutdown();
        try {
            if (!requestExecutor.awaitTermination(10L, java.util.concurrent.TimeUnit.SECONDS)) {
                requestExecutor.shutdownNow();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            requestExecutor.shutdownNow();
            throw new RuntimeException("Interrupted while closing HTTP server", exception);
        } finally {
            engine.close();
        }
    }

    private void handleExchange(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String route = routePattern(exchange.getRequestURI());
        RequestContext context = new RequestContext(resolveTraceId(exchange.getRequestHeaders()), Instant.now());
        AuthenticatedPrincipal principal = AuthenticatedPrincipal.anonymous();
        TenantScope tenantScope = TenantScope.all();
        long startedAtNanos = System.nanoTime();
        int statusCode = 500;
        try {
            principal = authorize(exchange, requiredRole(method, route));
            tenantScope = resolveTenantScope(exchange, principal, allowsAllTenants(route, method));
            RateLimitPolicy rateLimitPolicy = resolveRateLimitPolicy(tenantScope);
            if (requiresRateLimit(route) && rateLimitPolicy.enabled()
                    && !rateLimiter.tryAcquire(rateLimitKey(exchange, principal, tenantScope), rateLimitPolicy)) {
                metrics.recordRateLimited();
                statusCode = writeError(
                        exchange,
                        context,
                        new HttpStatusException(429, "Rate limit exceeded"),
                        Map.of("Retry-After", "1")
                );
                return;
            }

            TenantScope finalTenantScope = tenantScope;
            AuthenticatedPrincipal finalPrincipal = principal;
            statusCode = ScopedValue.where(CURRENT_REQUEST, context).call(() -> dispatch(exchange, context, finalPrincipal, finalTenantScope));
        } catch (ApiKeyAuthorizer.UnauthorizedException exception) {
            metrics.recordAuthFailure();
            statusCode = writeError(
                    exchange,
                    context,
                    new HttpStatusException(401, exception.getMessage()),
                    Map.of("WWW-Authenticate", "ApiKey")
            );
        } catch (HttpStatusException exception) {
            if (exception.statusCode() == 403) {
                metrics.recordAuthorizationDenied();
            }
            statusCode = writeError(exchange, context, exception, Map.of());
        } catch (Exception exception) {
            statusCode = writeError(exchange, context, exception, Map.of());
        } finally {
            long durationNanos = System.nanoTime() - startedAtNanos;
            metrics.recordRequest(method, route, statusCode, durationNanos);
            auditLogger.log(
                    Instant.now(),
                    context.traceId(),
                    principal,
                    method,
                    route,
                    statusCode,
                    remoteAddress(exchange)
            );
            emitRequestEvent(context.traceId(), principal.id(), method, route, statusCode, remoteAddress(exchange), durationNanos);
            exchange.close();
        }
    }

    private int dispatch(HttpExchange exchange, RequestContext context, AuthenticatedPrincipal principal, TenantScope tenantScope)
            throws IOException {
        List<String> path = pathSegments(exchange.getRequestURI());
        String method = exchange.getRequestMethod();

        if (path.size() == 1 && "health".equals(path.getFirst())) {
            requireMethod(method, "GET");
            return writeJson(exchange, 200, new HealthResponse("UP"), context);
        }

        if (path.size() == 1 && "metrics".equals(path.getFirst())) {
            requireMethod(method, "GET");
            return writeText(
                    exchange,
                    200,
                    metrics.scrape(engine, tenantScope.allTenants() ? null : tenantScope.tenantId()),
                    "text/plain; version=0.0.4; charset=utf-8",
                    context
            );
        }

        if (path.size() == 1 && "collections".equals(path.getFirst())) {
            return switch (method) {
                case "GET" -> writeJson(
                        exchange,
                        200,
                        tenantScope.allTenants() ? engine.listCollections() : engine.listCollections(tenantScope.tenantId()),
                        context
                );
                case "POST" -> createCollection(exchange, context, tenantScope.tenantId());
                default -> throw new HttpStatusException(405, "Method not allowed");
            };
        }

        if (path.size() == 2 && "collections".equals(path.getFirst())) {
            requireMethod(method, "GET");
            CollectionStats stats = engine.stats(tenantScope.tenantId(), path.get(1));
            return writeJson(exchange, 200, stats, context);
        }

        if (path.size() == 3 && "collections".equals(path.getFirst()) && "vectors".equals(path.get(2))) {
            requireMethod(method, "POST");
            return upsertVectors(exchange, context, tenantScope.tenantId(), path.get(1));
        }

        if (path.size() == 3 && "collections".equals(path.getFirst()) && "deletions".equals(path.get(2))) {
            requireMethod(method, "POST");
            return deleteVectors(exchange, context, tenantScope.tenantId(), path.get(1));
        }

        if (path.size() == 3 && "collections".equals(path.getFirst()) && "search".equals(path.get(2))) {
            requireMethod(method, "POST");
            return search(exchange, context, tenantScope.tenantId(), path.get(1));
        }

        if (path.size() == 3 && "collections".equals(path.getFirst()) && "compact".equals(path.get(2))) {
            requireMethod(method, "POST");
            return compact(exchange, context, tenantScope.tenantId(), path.get(1));
        }

        if (path.size() == 3 && "collections".equals(path.getFirst()) && "backup".equals(path.get(2))) {
            requireMethod(method, "POST");
            return backupCollection(exchange, context, tenantScope.tenantId(), path.get(1));
        }

        if (path.size() == 3 && "backups".equals(path.getFirst()) && "restore".equals(path.get(2))) {
            requireMethod(method, "POST");
            return restoreCollection(exchange, context, tenantScope.tenantId(), path.get(1));
        }

        throw new HttpStatusException(404, "Endpoint not found");
    }

    private int createCollection(HttpExchange exchange, RequestContext context, String tenantId) throws IOException {
        CreateCollectionRequest request = readBody(exchange, CreateCollectionRequest.class);
        if (!collectionExists(tenantId, request.name())) {
            enforceCollectionQuota(tenantId, tenantUsage(tenantId), 1);
        }
        var definition = engine.createCollection(tenantId, request);
        return writeJson(exchange, 201, engine.stats(definition.tenantId(), definition.name()), context);
    }

    private int upsertVectors(HttpExchange exchange, RequestContext context, String tenantId, String collectionName) throws IOException {
        UpsertVectorsRequest request = readBody(exchange, UpsertVectorsRequest.class);
        TenantUsage usage = tenantUsage(tenantId);
        enforceVectorQuota(
                tenantId,
                usage,
                engine.estimateAdditionalLiveVectors(tenantId, collectionName, request)
        );
        enforceStorageQuota(
                tenantId,
                usage,
                engine.estimateUpsertBytes(tenantId, collectionName, request)
        );
        engine.upsert(tenantId, collectionName, request);
        return writeJson(exchange, 200, engine.stats(tenantId, collectionName), context);
    }

    private int deleteVectors(HttpExchange exchange, RequestContext context, String tenantId, String collectionName) throws IOException {
        DeleteVectorsRequest request = readBody(exchange, DeleteVectorsRequest.class);
        engine.delete(tenantId, collectionName, request);
        return writeJson(exchange, 200, engine.stats(tenantId, collectionName), context);
    }

    private int search(HttpExchange exchange, RequestContext context, String tenantId, String collectionName) throws IOException {
        SearchRequest request = readBody(exchange, SearchRequest.class);
        long startedAtNanos = System.nanoTime();
        SearchResponse response = engine.search(tenantId, collectionName, request);
        long durationNanos = System.nanoTime() - startedAtNanos;
        boolean slowQuery = config.slowQueryThresholdMillis() > 0L
                && durationNanos >= config.slowQueryThresholdMillis() * 1_000_000L;
        metrics.recordSearch(tenantId, collectionName, response.hits().size(), durationNanos, slowQuery);
        emitSearchEvent(context.traceId(), tenantId + "/" + collectionName, request, response, durationNanos, slowQuery);
        return writeJson(exchange, 200, response, context);
    }

    private int compact(HttpExchange exchange, RequestContext context, String tenantId, String collectionName) throws IOException {
        engine.compact(tenantId, collectionName);
        return writeJson(exchange, 200, engine.stats(tenantId, collectionName), context);
    }

    private int backupCollection(HttpExchange exchange, RequestContext context, String tenantId, String collectionName) throws IOException {
        BackupCollectionRequest request = readBody(exchange, BackupCollectionRequest.class);
        return writeJson(
                exchange,
                200,
                engine.backupCollection(tenantId, collectionName, request.backupId(), config.backupDirectory()),
                context
        );
    }

    private int restoreCollection(HttpExchange exchange, RequestContext context, String tenantId, String backupId) throws IOException {
        RestoreCollectionRequest request = readBody(exchange, RestoreCollectionRequest.class);
        if (collectionExists(tenantId, request.collectionName())) {
            throw new HttpStatusException(409, "Collection already exists: " + tenantId + "/" + request.collectionName());
        }
        CollectionStats preview = engine.previewBackupCollection(tenantId, request.sourceCollection(), backupId, config.backupDirectory());
        TenantUsage usage = tenantUsage(tenantId);
        enforceCollectionQuota(tenantId, usage, 1);
        enforceVectorQuota(tenantId, usage, preview.liveVectorCount());
        enforceStorageQuota(tenantId, usage, preview.storageBytes());
        return writeJson(
                exchange,
                201,
                engine.restoreCollection(
                        tenantId,
                        request.sourceCollection(),
                        request.collectionName(),
                        backupId,
                        config.backupDirectory()
                ),
                context
        );
    }

    private <T> T readBody(HttpExchange exchange, Class<T> type) throws IOException {
        byte[] requestBody = exchange.getRequestBody().readAllBytes();
        if (requestBody.length == 0) {
            throw new ValidationException("Request body must not be empty");
        }
        return JsonSupport.read(requestBody, type);
    }

    private int writeJson(HttpExchange exchange, int statusCode, Object response, RequestContext context) throws IOException {
        byte[] body = JsonSupport.writeBytes(response);
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "application/json; charset=utf-8");
        headers.set("X-Trace-Id", context.traceId());
        exchange.sendResponseHeaders(statusCode, body.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(body);
        }
        return statusCode;
    }

    private int writeText(HttpExchange exchange, int statusCode, String response, String contentType, RequestContext context)
            throws IOException {
        byte[] body = response.getBytes(StandardCharsets.UTF_8);
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", contentType);
        headers.set("X-Trace-Id", context.traceId());
        exchange.sendResponseHeaders(statusCode, body.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(body);
        }
        return statusCode;
    }

    private int writeError(HttpExchange exchange, RequestContext context, Exception exception, Map<String, String> extraHeaders)
            throws IOException {
        Throwable cause = exception instanceof java.lang.reflect.InvocationTargetException invocation && invocation.getCause() != null
                ? invocation.getCause()
                : exception;

        int statusCode = switch (cause) {
            case ValidationException ignored -> 400;
            case IllegalArgumentException ignored -> 400;
            case NotFoundException ignored -> 404;
            case HttpStatusException http -> http.statusCode();
            default -> 500;
        };
        String message = cause instanceof HttpStatusException http ? http.getMessage() : cause.getMessage();
        if (message == null || message.isBlank()) {
            message = "Unexpected server error";
        }
        Headers headers = exchange.getResponseHeaders();
        extraHeaders.forEach(headers::set);
        return writeJson(exchange, statusCode, new ErrorResponse(message, context.traceId()), context);
    }

    private void requireMethod(String method, String expected) {
        if (!expected.equals(method)) {
            throw new HttpStatusException(405, "Method not allowed");
        }
    }

    private String resolveTraceId(Headers headers) {
        String traceId = headers.getFirst("X-Trace-Id");
        return traceId == null || traceId.isBlank() ? UUID.randomUUID().toString() : traceId;
    }

    private AuthenticatedPrincipal authorize(HttpExchange exchange, Role requiredRole) {
        if (requiredRole == null) {
            return authorizer.enabled() ? AuthenticatedPrincipal.anonymous() : AuthenticatedPrincipal.openAccess();
        }
        AuthenticatedPrincipal principal = authorizer.enabled()
                ? authorizer.authenticate(exchange.getRequestHeaders())
                : AuthenticatedPrincipal.openAccess();
        if (!principal.allows(requiredRole)) {
            throw new HttpStatusException(403, "Insufficient role for requested route");
        }
        return principal;
    }

    private boolean requiresRateLimit(String route) {
        return !"/health".equals(route);
    }

    private String rateLimitKey(HttpExchange exchange, AuthenticatedPrincipal principal, TenantScope tenantScope) {
        if (!tenantScope.allTenants()) {
            return "tenant:" + tenantScope.tenantId();
        }
        if (!"anonymous".equals(principal.id()) && !"public".equals(principal.id())) {
            return "principal:" + principal.id();
        }
        return "remote:" + remoteAddress(exchange);
    }

    private Role requiredRole(String method, String route) {
        return switch (route) {
            case "/health" -> null;
            case "/metrics" -> Role.ADMIN;
            case "/collections" -> "GET".equals(method) ? Role.READER : Role.WRITER;
            case "/collections/{name}" -> Role.READER;
            case "/collections/{name}/vectors" -> Role.WRITER;
            case "/collections/{name}/deletions" -> Role.WRITER;
            case "/collections/{name}/search" -> Role.READER;
            case "/collections/{name}/compact", "/collections/{name}/backup", "/backups/{id}/restore" -> Role.ADMIN;
            default -> null;
        };
    }

    private String routePattern(URI uri) {
        List<String> path = pathSegments(uri);
        if (path.size() == 1 && "health".equals(path.getFirst())) {
            return "/health";
        }
        if (path.size() == 1 && "metrics".equals(path.getFirst())) {
            return "/metrics";
        }
        if (path.size() == 1 && "collections".equals(path.getFirst())) {
            return "/collections";
        }
        if (path.size() == 2 && "collections".equals(path.getFirst())) {
            return "/collections/{name}";
        }
        if (path.size() == 3 && "collections".equals(path.getFirst())) {
            return switch (path.get(2)) {
                case "vectors" -> "/collections/{name}/vectors";
                case "deletions" -> "/collections/{name}/deletions";
                case "search" -> "/collections/{name}/search";
                case "compact" -> "/collections/{name}/compact";
                case "backup" -> "/collections/{name}/backup";
                default -> "/unknown";
            };
        }
        if (path.size() == 3 && "backups".equals(path.getFirst()) && "restore".equals(path.get(2))) {
            return "/backups/{id}/restore";
        }
        return "/unknown";
    }

    private List<String> pathSegments(URI uri) {
        return Arrays.stream(uri.getPath().split("/"))
                .filter(segment -> !segment.isBlank())
                .map(segment -> URLDecoder.decode(segment, StandardCharsets.UTF_8))
                .toList();
    }

    private TenantScope resolveTenantScope(HttpExchange exchange, AuthenticatedPrincipal principal, boolean allowAllTenants) {
        String requestedTenantId = tenantHeader(exchange.getRequestHeaders());
        if (requestedTenantId != null && !principal.canAccessTenant(requestedTenantId)) {
            throw new HttpStatusException(403, "Principal cannot access tenant " + requestedTenantId);
        }
        if (requestedTenantId != null) {
            return TenantScope.tenant(requestedTenantId, authorizer.tenantPolicy(requestedTenantId));
        }
        if (allowAllTenants && (!authorizer.enabled() || principal.globalTenantAccess())) {
            return TenantScope.all();
        }
        String tenantId = authorizer.enabled() ? principal.defaultTenantId() : CollectionDefinition.DEFAULT_TENANT;
        return TenantScope.tenant(tenantId, authorizer.tenantPolicy(tenantId));
    }

    private String tenantHeader(Headers headers) {
        String tenantId = headers.getFirst("X-Tenant-Id");
        if (tenantId == null || tenantId.isBlank()) {
            return null;
        }
        return CollectionDefinition.normalizeTenantId(tenantId);
    }

    private boolean allowsAllTenants(String route, String method) {
        return "/metrics".equals(route) || ("/collections".equals(route) && "GET".equals(method));
    }

    private RateLimitPolicy resolveRateLimitPolicy(TenantScope tenantScope) {
        if (tenantScope.allTenants()) {
            return rateLimiter.defaultPolicy();
        }
        RateLimitPolicy tenantPolicy = tenantScope.policy().rateLimitPolicy();
        return tenantPolicy == null ? rateLimiter.defaultPolicy() : tenantPolicy;
    }

    private TenantUsage tenantUsage(String tenantId) {
        List<CollectionStats> collections = engine.listCollections(tenantId);
        return new TenantUsage(
                collections.size(),
                collections.stream().mapToLong(CollectionStats::liveVectorCount).sum(),
                collections.stream().mapToLong(CollectionStats::storageBytes).sum()
        );
    }

    private boolean collectionExists(String tenantId, String collectionName) {
        return engine.listCollections(tenantId).stream().anyMatch(stats -> stats.name().equals(collectionName));
    }

    private void enforceCollectionQuota(String tenantId, TenantUsage usage, int additionalCollections) {
        Integer maxCollections = authorizer.tenantPolicy(tenantId).maxCollections();
        if (maxCollections != null && usage.collectionCount() + additionalCollections > maxCollections) {
            throw new HttpStatusException(409, "Tenant " + tenantId + " exceeded maxCollections quota");
        }
    }

    private void enforceVectorQuota(String tenantId, TenantUsage usage, long additionalLiveVectors) {
        Long maxLiveVectors = authorizer.tenantPolicy(tenantId).maxLiveVectors();
        if (maxLiveVectors != null && usage.liveVectorCount() + additionalLiveVectors > maxLiveVectors) {
            throw new HttpStatusException(409, "Tenant " + tenantId + " exceeded maxLiveVectors quota");
        }
    }

    private void enforceStorageQuota(String tenantId, TenantUsage usage, long additionalBytes) {
        Long maxStorageBytes = authorizer.tenantPolicy(tenantId).maxStorageBytes();
        if (maxStorageBytes != null && usage.storageBytes() + additionalBytes > maxStorageBytes) {
            throw new HttpStatusException(409, "Tenant " + tenantId + " exceeded maxStorageBytes quota");
        }
    }

    private String remoteAddress(HttpExchange exchange) {
        var address = exchange.getRemoteAddress().getAddress();
        return address == null ? exchange.getRemoteAddress().toString() : address.getHostAddress();
    }

    private void emitRequestEvent(
            String traceId,
            String principal,
            String method,
            String route,
            int statusCode,
            String remoteAddress,
            long durationNanos
    ) {
        HttpRequestJfrEvent event = new HttpRequestJfrEvent();
        if (!event.isEnabled()) {
            return;
        }
        event.traceId = traceId;
        event.principal = principal;
        event.method = method;
        event.route = route;
        event.statusCode = statusCode;
        event.remoteAddress = remoteAddress;
        event.durationMillis = durationNanos / 1_000_000L;
        event.commit();
    }

    private void emitSearchEvent(
            String traceId,
            String collectionName,
            SearchRequest request,
            SearchResponse response,
            long durationNanos,
            boolean slowQuery
    ) {
        SearchJfrEvent event = new SearchJfrEvent();
        if (!event.isEnabled()) {
            return;
        }
        event.traceId = traceId;
        event.collection = collectionName;
        event.topK = request.topK();
        event.filterClauses = request.filter() == null ? 0 : request.filter().size();
        event.resultCount = response.hits().size();
        event.slowQuery = slowQuery;
        event.durationMillis = durationNanos / 1_000_000L;
        event.commit();
    }

    private static final class HttpStatusException extends RuntimeException {
        private final int statusCode;

        private HttpStatusException(int statusCode, String message) {
            super(message);
            this.statusCode = statusCode;
        }

        private int statusCode() {
            return statusCode;
        }
    }

    private record TenantScope(String tenantId, TenantPolicy policy, boolean allTenants) {
        private static TenantScope all() {
            return new TenantScope(null, null, true);
        }

        private static TenantScope tenant(String tenantId, TenantPolicy policy) {
            return new TenantScope(
                    CollectionDefinition.normalizeTenantId(tenantId),
                    policy == null ? TenantPolicy.unrestricted(tenantId) : policy,
                    false
            );
        }
    }

    private record TenantUsage(int collectionCount, long liveVectorCount, long storageBytes) {
    }
}
