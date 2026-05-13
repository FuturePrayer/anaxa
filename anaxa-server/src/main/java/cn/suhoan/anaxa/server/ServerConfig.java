package cn.suhoan.anaxa.server;

import cn.suhoan.anaxa.engine.VectorDatabaseEngine;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
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
        boolean webUiEnabled
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
                true
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
                true
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
                true
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
                true
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
                true
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
                true
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
                true
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
        if (!allowOpenAccess && apiKeys.isEmpty() && apiKeyFile == null) {
            throw new IllegalArgumentException(
                    "API keys are required unless --allow-open-access=true is explicitly set"
            );
        }
    }

    public static ServerConfig fromArgs(String[] args) {
        String host = "0.0.0.0";
        int port = 8080;
        Path dataDirectory = Paths.get("data");
        long flushThresholdBytes = VectorDatabaseEngine.DEFAULT_FLUSH_THRESHOLD_BYTES;
        Set<String> apiKeys = Set.of();
        Path apiKeyFile = null;
        int rateLimitPerMinute = 6_000;
        int rateLimitBurst = 256;
        long slowQueryThresholdMillis = 250L;
        Path auditLogPath = null;
        Path backupDirectory = null;
        long snapshotIntervalSeconds = 0L;
        int autoSnapshotRetentionPerCollection = 7;
        long maxRequestBodyBytes = DEFAULT_MAX_REQUEST_BODY_BYTES;
        int maxConcurrentRequests = DEFAULT_MAX_CONCURRENT_REQUESTS;
        boolean allowOpenAccess = false;
        boolean webUiEnabled = true;

        for (String arg : args) {
            if (!arg.startsWith("--") || !arg.contains("=")) {
                throw new IllegalArgumentException("Arguments must use --key=value syntax: " + arg);
            }

            String key = arg.substring(2, arg.indexOf('='));
            String value = arg.substring(arg.indexOf('=') + 1);
            switch (key) {
                case "host" -> host = value;
                case "port" -> port = Integer.parseInt(value);
                case "data-dir" -> dataDirectory = Paths.get(value);
                case "default-flush-threshold-bytes" -> flushThresholdBytes = Long.parseLong(value);
                case "api-keys" -> apiKeys = parseApiKeys(value);
                case "api-key-file" -> apiKeyFile = Paths.get(value);
                case "rate-limit-per-minute" -> rateLimitPerMinute = Integer.parseInt(value);
                case "rate-limit-burst" -> rateLimitBurst = Integer.parseInt(value);
                case "slow-query-threshold-ms" -> slowQueryThresholdMillis = Long.parseLong(value);
                case "audit-log" -> auditLogPath = Paths.get(value);
                case "backup-dir" -> backupDirectory = Paths.get(value);
                case "snapshot-interval-seconds" -> snapshotIntervalSeconds = Long.parseLong(value);
                case "snapshot-retention-per-collection" -> autoSnapshotRetentionPerCollection = Integer.parseInt(value);
                case "max-request-body-bytes" -> maxRequestBodyBytes = Long.parseLong(value);
                case "max-concurrent-requests" -> maxConcurrentRequests = Integer.parseInt(value);
                case "allow-open-access" -> allowOpenAccess = Boolean.parseBoolean(value);
                case "web-ui-enabled" -> webUiEnabled = Boolean.parseBoolean(value);
                default -> throw new IllegalArgumentException("Unknown argument: --" + key);
            }
        }

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
                webUiEnabled
        );
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
}
