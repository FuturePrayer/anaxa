package cn.suhoan.anaxa.server;

import cn.suhoan.anaxa.common.json.JsonSupport;
import cn.suhoan.anaxa.engine.EngineOptions;
import cn.suhoan.anaxa.engine.VectorDatabaseEngine;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public record ServerConfig(
        String host,
        int port,
        Path dataDirectory,
        long defaultFlushThresholdBytes,
        Set<String> apiKeys,
        Path apiKeyFile,
        int rateLimitPerMinute,
        int rateLimitBurst,
        long slowQueryThresholdMillis,
        Path auditLogPath,
        Path backupDirectory,
        long snapshotIntervalSeconds,
        int autoSnapshotRetentionPerCollection,
        long maxRequestBodyBytes,
        int maxConcurrentRequests,
        boolean allowOpenAccess,
        boolean webUiEnabled,
        EngineOptions engineOptions
) {
    public static final long DEFAULT_MAX_REQUEST_BODY_BYTES = 256L * 1024L * 1024L;
    public static final int DEFAULT_MAX_CONCURRENT_REQUESTS = 1_024;

    public ServerConfig(String host, int port, Path dataDirectory, long defaultFlushThresholdBytes) {
        this(
                host,
                port,
                dataDirectory,
                defaultFlushThresholdBytes,
                Set.of(),
                null,
                6_000,
                256,
                250L,
                dataDirectory.resolve("audit").resolve("audit.log"),
                dataDirectory.resolve("backups"),
                0L,
                7,
                DEFAULT_MAX_REQUEST_BODY_BYTES,
                DEFAULT_MAX_CONCURRENT_REQUESTS,
                false,
                true,
                EngineOptions.defaults()
        );
    }

    public static ServerConfig openAccess(String host, int port, Path dataDirectory, long defaultFlushThresholdBytes) {
        return new ServerConfig(
                host,
                port,
                dataDirectory,
                defaultFlushThresholdBytes,
                Set.of(),
                null,
                6_000,
                256,
                250L,
                dataDirectory.resolve("audit").resolve("audit.log"),
                dataDirectory.resolve("backups"),
                0L,
                7,
                DEFAULT_MAX_REQUEST_BODY_BYTES,
                DEFAULT_MAX_CONCURRENT_REQUESTS,
                true,
                true,
                EngineOptions.defaults()
        );
    }

    public ServerConfig(
            String host,
            int port,
            Path dataDirectory,
            long defaultFlushThresholdBytes,
            Set<String> apiKeys,
            int rateLimitPerMinute,
            int rateLimitBurst
    ) {
        this(
                host,
                port,
                dataDirectory,
                defaultFlushThresholdBytes,
                apiKeys,
                null,
                rateLimitPerMinute,
                rateLimitBurst,
                250L,
                dataDirectory.resolve("audit").resolve("audit.log"),
                dataDirectory.resolve("backups"),
                0L,
                7,
                DEFAULT_MAX_REQUEST_BODY_BYTES,
                DEFAULT_MAX_CONCURRENT_REQUESTS,
                false,
                true,
                EngineOptions.defaults()
        );
    }

    public ServerConfig(
            String host,
            int port,
            Path dataDirectory,
            long defaultFlushThresholdBytes,
            Set<String> apiKeys,
            Path apiKeyFile,
            int rateLimitPerMinute,
            int rateLimitBurst,
            long slowQueryThresholdMillis,
            Path auditLogPath,
            Path backupDirectory
    ) {
        this(
                host,
                port,
                dataDirectory,
                defaultFlushThresholdBytes,
                apiKeys,
                apiKeyFile,
                rateLimitPerMinute,
                rateLimitBurst,
                slowQueryThresholdMillis,
                auditLogPath,
                backupDirectory,
                0L,
                7,
                DEFAULT_MAX_REQUEST_BODY_BYTES,
                DEFAULT_MAX_CONCURRENT_REQUESTS,
                false,
                true,
                EngineOptions.defaults()
        );
    }

    public ServerConfig(
            String host,
            int port,
            Path dataDirectory,
            long defaultFlushThresholdBytes,
            Set<String> apiKeys,
            Path apiKeyFile,
            int rateLimitPerMinute,
            int rateLimitBurst,
            long slowQueryThresholdMillis,
            Path auditLogPath,
            Path backupDirectory,
            long snapshotIntervalSeconds,
            int autoSnapshotRetentionPerCollection
    ) {
        this(
                host,
                port,
                dataDirectory,
                defaultFlushThresholdBytes,
                apiKeys,
                apiKeyFile,
                rateLimitPerMinute,
                rateLimitBurst,
                slowQueryThresholdMillis,
                auditLogPath,
                backupDirectory,
                snapshotIntervalSeconds,
                autoSnapshotRetentionPerCollection,
                DEFAULT_MAX_REQUEST_BODY_BYTES,
                DEFAULT_MAX_CONCURRENT_REQUESTS,
                false,
                true,
                EngineOptions.defaults()
        );
    }

    public ServerConfig(
            String host,
            int port,
            Path dataDirectory,
            long defaultFlushThresholdBytes,
            Set<String> apiKeys,
            Path apiKeyFile,
            int rateLimitPerMinute,
            int rateLimitBurst,
            long slowQueryThresholdMillis,
            Path auditLogPath,
            Path backupDirectory,
            long snapshotIntervalSeconds,
            int autoSnapshotRetentionPerCollection,
            long maxRequestBodyBytes,
            int maxConcurrentRequests
    ) {
        this(
                host,
                port,
                dataDirectory,
                defaultFlushThresholdBytes,
                apiKeys,
                apiKeyFile,
                rateLimitPerMinute,
                rateLimitBurst,
                slowQueryThresholdMillis,
                auditLogPath,
                backupDirectory,
                snapshotIntervalSeconds,
                autoSnapshotRetentionPerCollection,
                maxRequestBodyBytes,
                maxConcurrentRequests,
                false,
                true,
                EngineOptions.defaults()
        );
    }

    public ServerConfig(
            String host,
            int port,
            Path dataDirectory,
            long defaultFlushThresholdBytes,
            Set<String> apiKeys,
            Path apiKeyFile,
            int rateLimitPerMinute,
            int rateLimitBurst,
            long slowQueryThresholdMillis,
            Path auditLogPath,
            Path backupDirectory,
            long snapshotIntervalSeconds,
            int autoSnapshotRetentionPerCollection,
            long maxRequestBodyBytes,
            int maxConcurrentRequests,
            boolean allowOpenAccess
    ) {
        this(
                host,
                port,
                dataDirectory,
                defaultFlushThresholdBytes,
                apiKeys,
                apiKeyFile,
                rateLimitPerMinute,
                rateLimitBurst,
                slowQueryThresholdMillis,
                auditLogPath,
                backupDirectory,
                snapshotIntervalSeconds,
                autoSnapshotRetentionPerCollection,
                maxRequestBodyBytes,
                maxConcurrentRequests,
                allowOpenAccess,
                true,
                EngineOptions.defaults()
        );
    }

    public ServerConfig(
            String host,
            int port,
            Path dataDirectory,
            long defaultFlushThresholdBytes,
            Set<String> apiKeys,
            Path apiKeyFile,
            int rateLimitPerMinute,
            int rateLimitBurst,
            long slowQueryThresholdMillis,
            Path auditLogPath,
            Path backupDirectory,
            long snapshotIntervalSeconds,
            int autoSnapshotRetentionPerCollection,
            long maxRequestBodyBytes,
            int maxConcurrentRequests,
            boolean allowOpenAccess,
            boolean webUiEnabled
    ) {
        this(
                host,
                port,
                dataDirectory,
                defaultFlushThresholdBytes,
                apiKeys,
                apiKeyFile,
                rateLimitPerMinute,
                rateLimitBurst,
                slowQueryThresholdMillis,
                auditLogPath,
                backupDirectory,
                snapshotIntervalSeconds,
                autoSnapshotRetentionPerCollection,
                maxRequestBodyBytes,
                maxConcurrentRequests,
                allowOpenAccess,
                webUiEnabled,
                EngineOptions.defaults()
        );
    }

    public ServerConfig {
        host = Objects.requireNonNull(host, "host").trim();
        if (host.isEmpty()) {
            throw new IllegalArgumentException("host must not be blank");
        }
        if (port < 0 || port > 65_535) {
            throw new IllegalArgumentException("port must be between 0 and 65535");
        }
        dataDirectory = Objects.requireNonNull(dataDirectory, "dataDirectory");
        if (defaultFlushThresholdBytes <= 0L) {
            throw new IllegalArgumentException("defaultFlushThresholdBytes must be positive");
        }
        apiKeys = Set.copyOf(Objects.requireNonNull(apiKeys, "apiKeys"));
        if (rateLimitPerMinute < 0) {
            throw new IllegalArgumentException("rateLimitPerMinute must not be negative");
        }
        if (rateLimitBurst < 0) {
            throw new IllegalArgumentException("rateLimitBurst must not be negative");
        }
        if (slowQueryThresholdMillis < 0L) {
            throw new IllegalArgumentException("slowQueryThresholdMillis must not be negative");
        }
        auditLogPath = auditLogPath == null ? dataDirectory.resolve("audit").resolve("audit.log") : auditLogPath;
        backupDirectory = backupDirectory == null ? dataDirectory.resolve("backups") : backupDirectory;
        if (snapshotIntervalSeconds < 0L) {
            throw new IllegalArgumentException("snapshotIntervalSeconds must not be negative");
        }
        if (autoSnapshotRetentionPerCollection < 0) {
            throw new IllegalArgumentException("autoSnapshotRetentionPerCollection must not be negative");
        }
        if (maxRequestBodyBytes < 0L) {
            throw new IllegalArgumentException("maxRequestBodyBytes must not be negative");
        }
        if (maxConcurrentRequests <= 0) {
            throw new IllegalArgumentException("maxConcurrentRequests must be positive");
        }
        engineOptions = Objects.requireNonNullElse(engineOptions, EngineOptions.defaults());
        if (!allowOpenAccess && apiKeys.isEmpty() && apiKeyFile == null) {
            throw new IllegalArgumentException(
                    "API keys are required unless --allow-open-access=true is explicitly set"
            );
        }
    }

    public static ServerConfig fromArgs(String[] args) {
        Path configPath = null;
        MutableConfig values = new MutableConfig();
        for (String arg : args) {
            if (!arg.startsWith("--") || !arg.contains("=")) {
                throw new IllegalArgumentException("Arguments must use --key=value syntax: " + arg);
            }
            String key = arg.substring(2, arg.indexOf('='));
            String value = arg.substring(arg.indexOf('=') + 1);
            if ("config".equals(key)) {
                configPath = Paths.get(value);
                values = loadConfig(configPath);
                break;
            }
        }

        for (String arg : args) {
            String key = arg.substring(2, arg.indexOf('='));
            if (!"config".equals(key)) {
                applyArg(values, arg);
            }
        }
        return values.toServerConfig();
    }

    public static void writeTemplate(Path path) {
        try {
            Path parent = path.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(path, templateJson());
        } catch (IOException exception) {
            throw new UncheckedIOException("Failed to write config template to " + path, exception);
        }
    }

    private static MutableConfig loadConfig(Path path) {
        try {
            Map<String, Object> root = JsonSupport.readMap(Files.readAllBytes(path));
            MutableConfig values = new MutableConfig();
            applyRoot(values, root);
            Object engine = root.get("engine");
            if (engine instanceof Map<?, ?> map) {
                applyEngine(values, map);
            }
            return values;
        } catch (IOException exception) {
            throw new UncheckedIOException("Failed to read config file " + path, exception);
        }
    }

    private static void applyArg(MutableConfig values, String arg) {
        if (!arg.startsWith("--") || !arg.contains("=")) {
            throw new IllegalArgumentException("Arguments must use --key=value syntax: " + arg);
        }

        String key = arg.substring(2, arg.indexOf('='));
        String value = arg.substring(arg.indexOf('=') + 1);
        switch (key) {
            case "host" -> values.host = value;
            case "port" -> values.port = Integer.parseInt(value);
            case "data-dir" -> values.dataDirectory = Paths.get(value);
            case "default-flush-threshold-bytes" -> values.flushThresholdBytes = Long.parseLong(value);
            case "api-keys" -> values.apiKeys = parseApiKeys(value);
            case "api-key-file" -> values.apiKeyFile = Paths.get(value);
            case "rate-limit-per-minute" -> values.rateLimitPerMinute = Integer.parseInt(value);
            case "rate-limit-burst" -> values.rateLimitBurst = Integer.parseInt(value);
            case "slow-query-threshold-ms" -> values.slowQueryThresholdMillis = Long.parseLong(value);
            case "audit-log" -> values.auditLogPath = Paths.get(value);
            case "backup-dir" -> values.backupDirectory = Paths.get(value);
            case "snapshot-interval-seconds" -> values.snapshotIntervalSeconds = Long.parseLong(value);
            case "snapshot-retention-per-collection" -> values.autoSnapshotRetentionPerCollection = Integer.parseInt(value);
            case "max-request-body-bytes" -> values.maxRequestBodyBytes = Long.parseLong(value);
            case "max-concurrent-requests" -> values.maxConcurrentRequests = Integer.parseInt(value);
            case "allow-open-access" -> values.allowOpenAccess = Boolean.parseBoolean(value);
            case "web-ui-enabled" -> values.webUiEnabled = Boolean.parseBoolean(value);
            case "max-concurrent-source-searches" -> values.maxConcurrentSourceSearches = Integer.parseInt(value);
            case "warmup-yield-poll-ms" -> values.warmupYieldPollMillis = Long.parseLong(value);
            case "foreground-searches-per-source-search" -> values.foregroundSearchesPerSourceSearch = Integer.parseInt(value);
            case "min-adaptive-source-searches" -> values.minAdaptiveSourceSearches = Integer.parseInt(value);
            case "adaptive-recovery-searches" -> values.adaptiveRecoverySearches = Integer.parseInt(value);
            default -> throw new IllegalArgumentException("Unknown argument: --" + key);
        }
    }

    private static void applyRoot(MutableConfig values, Map<String, Object> root) {
        if (root.containsKey("host")) values.host = string(root, "host");
        if (root.containsKey("port")) values.port = integer(root, "port");
        if (root.containsKey("dataDirectory")) values.dataDirectory = Paths.get(string(root, "dataDirectory"));
        if (root.containsKey("dataDir")) values.dataDirectory = Paths.get(string(root, "dataDir"));
        if (root.containsKey("defaultFlushThresholdBytes")) values.flushThresholdBytes = longValue(root, "defaultFlushThresholdBytes");
        if (root.containsKey("apiKeys")) values.apiKeys = parseApiKeys(root.get("apiKeys"));
        if (root.containsKey("apiKeyFile")) values.apiKeyFile = pathOrNull(root, "apiKeyFile");
        if (root.containsKey("rateLimitPerMinute")) values.rateLimitPerMinute = integer(root, "rateLimitPerMinute");
        if (root.containsKey("rateLimitBurst")) values.rateLimitBurst = integer(root, "rateLimitBurst");
        if (root.containsKey("slowQueryThresholdMillis")) values.slowQueryThresholdMillis = longValue(root, "slowQueryThresholdMillis");
        if (root.containsKey("auditLogPath")) values.auditLogPath = pathOrNull(root, "auditLogPath");
        if (root.containsKey("backupDirectory")) values.backupDirectory = pathOrNull(root, "backupDirectory");
        if (root.containsKey("snapshotIntervalSeconds")) values.snapshotIntervalSeconds = longValue(root, "snapshotIntervalSeconds");
        if (root.containsKey("autoSnapshotRetentionPerCollection")) values.autoSnapshotRetentionPerCollection = integer(root, "autoSnapshotRetentionPerCollection");
        if (root.containsKey("maxRequestBodyBytes")) values.maxRequestBodyBytes = longValue(root, "maxRequestBodyBytes");
        if (root.containsKey("maxConcurrentRequests")) values.maxConcurrentRequests = integer(root, "maxConcurrentRequests");
        if (root.containsKey("allowOpenAccess")) values.allowOpenAccess = bool(root, "allowOpenAccess");
        if (root.containsKey("webUiEnabled")) values.webUiEnabled = bool(root, "webUiEnabled");
    }

    private static void applyEngine(MutableConfig values, Map<?, ?> engine) {
        if (engine.containsKey("maxConcurrentSourceSearches")) values.maxConcurrentSourceSearches = integer(engine, "maxConcurrentSourceSearches");
        if (engine.containsKey("warmupYieldPollMillis")) values.warmupYieldPollMillis = longValue(engine, "warmupYieldPollMillis");
        if (engine.containsKey("foregroundSearchesPerSourceSearch")) values.foregroundSearchesPerSourceSearch = integer(engine, "foregroundSearchesPerSourceSearch");
        if (engine.containsKey("minAdaptiveSourceSearches")) values.minAdaptiveSourceSearches = integer(engine, "minAdaptiveSourceSearches");
        if (engine.containsKey("adaptiveRecoverySearches")) values.adaptiveRecoverySearches = integer(engine, "adaptiveRecoverySearches");
    }

    private static String templateJson() {
        return """
                {
                  "host": "0.0.0.0",
                  "port": 30720,
                  "dataDirectory": "data",
                  "defaultFlushThresholdBytes": 67108864,
                  "apiKeys": [],
                  "apiKeyFile": null,
                  "rateLimitPerMinute": 6000,
                  "rateLimitBurst": 256,
                  "slowQueryThresholdMillis": 250,
                  "auditLogPath": null,
                  "backupDirectory": null,
                  "snapshotIntervalSeconds": 0,
                  "autoSnapshotRetentionPerCollection": 7,
                  "maxRequestBodyBytes": 268435456,
                  "maxConcurrentRequests": 1024,
                  "allowOpenAccess": false,
                  "webUiEnabled": true,
                  "engine": {
                    "maxConcurrentSourceSearches": 4,
                    "warmupYieldPollMillis": 2,
                    "foregroundSearchesPerSourceSearch": 16,
                    "minAdaptiveSourceSearches": 1,
                    "adaptiveRecoverySearches": 64
                  }
                }
                """;
    }

    private static Set<String> parseApiKeys(String value) {
        if (value.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(token -> !token.isEmpty())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static Set<String> parseApiKeys(Object value) {
        if (value instanceof List<?> list) {
            return list.stream()
                    .filter(Objects::nonNull)
                    .map(Object::toString)
                    .map(String::trim)
                    .filter(token -> !token.isEmpty())
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
        }
        return parseApiKeys(value == null ? "" : value.toString());
    }

    private static String string(Map<?, ?> values, String key) {
        Object value = values.get(key);
        return value == null ? null : value.toString();
    }

    private static Path pathOrNull(Map<?, ?> values, String key) {
        String value = string(values, key);
        return value == null || value.isBlank() ? null : Paths.get(value);
    }

    private static int integer(Map<?, ?> values, String key) {
        Object value = values.get(key);
        return value instanceof Number number ? number.intValue() : Integer.parseInt(value.toString());
    }

    private static long longValue(Map<?, ?> values, String key) {
        Object value = values.get(key);
        return value instanceof Number number ? number.longValue() : Long.parseLong(value.toString());
    }

    private static boolean bool(Map<?, ?> values, String key) {
        Object value = values.get(key);
        return value instanceof Boolean bool ? bool : Boolean.parseBoolean(value.toString());
    }

    private static final class MutableConfig {
        private String host = "0.0.0.0";
        private int port = 30720;
        private Path dataDirectory = Paths.get("data");
        private long flushThresholdBytes = VectorDatabaseEngine.DEFAULT_FLUSH_THRESHOLD_BYTES;
        private Set<String> apiKeys = Set.of();
        private Path apiKeyFile;
        private int rateLimitPerMinute = 6_000;
        private int rateLimitBurst = 256;
        private long slowQueryThresholdMillis = 250L;
        private Path auditLogPath;
        private Path backupDirectory;
        private long snapshotIntervalSeconds;
        private int autoSnapshotRetentionPerCollection = 7;
        private long maxRequestBodyBytes = DEFAULT_MAX_REQUEST_BODY_BYTES;
        private int maxConcurrentRequests = DEFAULT_MAX_CONCURRENT_REQUESTS;
        private boolean allowOpenAccess;
        private boolean webUiEnabled = true;
        private Integer maxConcurrentSourceSearches;
        private Long warmupYieldPollMillis;
        private Integer foregroundSearchesPerSourceSearch;
        private Integer minAdaptiveSourceSearches;
        private Integer adaptiveRecoverySearches;

        private ServerConfig toServerConfig() {
            return new ServerConfig(
                    host,
                    port,
                    dataDirectory,
                    flushThresholdBytes,
                    apiKeys,
                    apiKeyFile,
                    rateLimitPerMinute,
                    rateLimitBurst,
                    slowQueryThresholdMillis,
                    auditLogPath,
                    backupDirectory,
                    snapshotIntervalSeconds,
                    autoSnapshotRetentionPerCollection,
                    maxRequestBodyBytes,
                    maxConcurrentRequests,
                    allowOpenAccess,
                    webUiEnabled,
                    EngineOptions.ofNullable(
                            maxConcurrentSourceSearches,
                            warmupYieldPollMillis,
                            foregroundSearchesPerSourceSearch,
                            minAdaptiveSourceSearches,
                            adaptiveRecoverySearches
                    )
            );
        }
    }
}
