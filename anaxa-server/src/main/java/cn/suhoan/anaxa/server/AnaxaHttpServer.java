package cn.suhoan.anaxa.server;

import cn.suhoan.anaxa.common.context.RequestContext;
import cn.suhoan.anaxa.common.error.NotFoundException;
import cn.suhoan.anaxa.common.error.ValidationException;
import cn.suhoan.anaxa.common.json.JsonSupport;
import cn.suhoan.anaxa.common.model.CollectionStats;
import cn.suhoan.anaxa.common.model.CreateCollectionRequest;
import cn.suhoan.anaxa.common.model.ErrorResponse;
import cn.suhoan.anaxa.common.model.HealthResponse;
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
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class AnaxaHttpServer implements AutoCloseable {
    private static final ScopedValue<RequestContext> CURRENT_REQUEST = ScopedValue.newInstance();

    private final VectorDatabaseEngine engine;
    private final HttpServer server;
    private final ExecutorService requestExecutor;

    public AnaxaHttpServer(ServerConfig config) throws IOException {
        this(new VectorDatabaseEngine(config.dataDirectory(), config.defaultFlushThresholdBytes()), config);
    }

    AnaxaHttpServer(VectorDatabaseEngine engine, ServerConfig config) throws IOException {
        this.engine = engine;
        this.server = HttpServer.create(new InetSocketAddress(config.host(), config.port()), 0);
        this.requestExecutor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("anaxa-http-", 0).factory());
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
        server.stop(0);
        requestExecutor.shutdown();
        engine.close();
    }

    private void handleExchange(HttpExchange exchange) throws IOException {
        RequestContext context = new RequestContext(resolveTraceId(exchange.getRequestHeaders()), Instant.now());
        try {
            ScopedValue.where(CURRENT_REQUEST, context).call(() -> {
                dispatch(exchange, context);
                return null;
            });
        } catch (Exception exception) {
            writeError(exchange, context, exception);
        } finally {
            exchange.close();
        }
    }

    private void dispatch(HttpExchange exchange, RequestContext context) throws IOException {
        List<String> path = pathSegments(exchange.getRequestURI());
        String method = exchange.getRequestMethod();

        if (path.size() == 1 && "health".equals(path.getFirst())) {
            requireMethod(method, "GET");
            writeJson(exchange, 200, new HealthResponse("UP"), context);
            return;
        }

        if (path.size() == 1 && "collections".equals(path.getFirst())) {
            switch (method) {
                case "GET" -> writeJson(exchange, 200, engine.listCollections(), context);
                case "POST" -> createCollection(exchange, context);
                default -> throw new HttpStatusException(405, "Method not allowed");
            }
            return;
        }

        if (path.size() == 2 && "collections".equals(path.getFirst())) {
            requireMethod(method, "GET");
            CollectionStats stats = engine.stats(path.get(1));
            writeJson(exchange, 200, stats, context);
            return;
        }

        if (path.size() == 3 && "collections".equals(path.getFirst()) && "vectors".equals(path.get(2))) {
            requireMethod(method, "POST");
            upsertVectors(exchange, context, path.get(1));
            return;
        }

        if (path.size() == 3 && "collections".equals(path.getFirst()) && "search".equals(path.get(2))) {
            requireMethod(method, "POST");
            search(exchange, context, path.get(1));
            return;
        }

        throw new HttpStatusException(404, "Endpoint not found");
    }

    private void createCollection(HttpExchange exchange, RequestContext context) throws IOException {
        CreateCollectionRequest request = readBody(exchange, CreateCollectionRequest.class);
        var definition = engine.createCollection(request);
        writeJson(exchange, 201, engine.stats(definition.name()), context);
    }

    private void upsertVectors(HttpExchange exchange, RequestContext context, String collectionName) throws IOException {
        UpsertVectorsRequest request = readBody(exchange, UpsertVectorsRequest.class);
        engine.upsert(collectionName, request);
        writeJson(exchange, 200, engine.stats(collectionName), context);
    }

    private void search(HttpExchange exchange, RequestContext context, String collectionName) throws IOException {
        SearchRequest request = readBody(exchange, SearchRequest.class);
        SearchResponse response = engine.search(collectionName, request);
        writeJson(exchange, 200, response, context);
    }

    private <T> T readBody(HttpExchange exchange, Class<T> type) throws IOException {
        byte[] requestBody = exchange.getRequestBody().readAllBytes();
        if (requestBody.length == 0) {
            throw new ValidationException("Request body must not be empty");
        }
        return JsonSupport.read(requestBody, type);
    }

    private void writeJson(HttpExchange exchange, int statusCode, Object response, RequestContext context) throws IOException {
        byte[] body = JsonSupport.writeBytes(response);
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "application/json; charset=utf-8");
        headers.set("X-Trace-Id", context.traceId());
        exchange.sendResponseHeaders(statusCode, body.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(body);
        }
    }

    private void writeError(HttpExchange exchange, RequestContext context, Exception exception) throws IOException {
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
        writeJson(exchange, statusCode, new ErrorResponse(message, context.traceId()), context);
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

    private List<String> pathSegments(URI uri) {
        return Arrays.stream(uri.getPath().split("/"))
                .filter(segment -> !segment.isBlank())
                .map(segment -> URLDecoder.decode(segment, StandardCharsets.UTF_8))
                .toList();
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
}
