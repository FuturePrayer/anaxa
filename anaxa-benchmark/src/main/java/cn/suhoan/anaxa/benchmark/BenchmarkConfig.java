package cn.suhoan.anaxa.benchmark;

import cn.suhoan.anaxa.engine.VectorDatabaseEngine;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Objects;

public record BenchmarkConfig(
        String baseUrl,
        String host,
        int port,
        Path dataDirectory,
        boolean keepData,
        String apiKey,
        String tenantId,
        long defaultFlushThresholdBytes,
        int rateLimitPerMinute,
        int rateLimitBurst,
        long slowQueryThresholdMillis,
        ScenarioPreset preset,
        String collectionPrefix,
        Duration requestTimeout
) {
    public BenchmarkConfig {
        baseUrl = baseUrl == null || baseUrl.isBlank() ? null : stripTrailingSlash(baseUrl);
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
        if (rateLimitPerMinute < 0) {
            throw new IllegalArgumentException("rateLimitPerMinute must not be negative");
        }
        if (rateLimitBurst < 0) {
            throw new IllegalArgumentException("rateLimitBurst must not be negative");
        }
        if (slowQueryThresholdMillis < 0L) {
            throw new IllegalArgumentException("slowQueryThresholdMillis must not be negative");
        }
        preset = Objects.requireNonNull(preset, "preset");
        collectionPrefix = normalizeCollectionPrefix(collectionPrefix);
        requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
        if (requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new IllegalArgumentException("requestTimeout must be positive");
        }
    }

    public boolean embeddedServer() {
        return baseUrl == null;
    }

    public static BenchmarkConfig fromArgs(String[] args) {
        String baseUrl = null;
        String host = "127.0.0.1";
        int port = 0;
        Path dataDirectory = Paths.get("benchmark-data");
        boolean keepData = false;
        String apiKey = null;
        String tenantId = null;
        long defaultFlushThresholdBytes = VectorDatabaseEngine.DEFAULT_FLUSH_THRESHOLD_BYTES;
        int rateLimitPerMinute = 1_000_000;
        int rateLimitBurst = 100_000;
        long slowQueryThresholdMillis = 1_000L;
        ScenarioPreset preset = ScenarioPreset.STANDARD;
        String collectionPrefix = "envbench";
        Duration requestTimeout = Duration.ofSeconds(120L);

        for (String arg : args) {
            if ("--keep-data".equals(arg)) {
                keepData = true;
                continue;
            }
            if (!arg.startsWith("--") || !arg.contains("=")) {
                throw new IllegalArgumentException("Arguments must use --key=value syntax: " + arg);
            }

            String key = arg.substring(2, arg.indexOf('='));
            String value = arg.substring(arg.indexOf('=') + 1);
            switch (key) {
                case "base-url" -> baseUrl = value;
                case "host" -> host = value;
                case "port" -> port = Integer.parseInt(value);
                case "data-dir" -> dataDirectory = Paths.get(value);
                case "keep-data" -> keepData = Boolean.parseBoolean(value);
                case "api-key" -> apiKey = value;
                case "tenant-id" -> tenantId = value;
                case "default-flush-threshold-bytes" -> defaultFlushThresholdBytes = Long.parseLong(value);
                case "rate-limit-per-minute" -> rateLimitPerMinute = Integer.parseInt(value);
                case "rate-limit-burst" -> rateLimitBurst = Integer.parseInt(value);
                case "slow-query-threshold-ms" -> slowQueryThresholdMillis = Long.parseLong(value);
                case "profile" -> preset = ScenarioPreset.fromCliValue(value);
                case "collection-prefix" -> collectionPrefix = value;
                case "request-timeout-seconds" -> requestTimeout = Duration.ofSeconds(Long.parseLong(value));
                default -> throw new IllegalArgumentException("Unknown argument: --" + key);
            }
        }

        return new BenchmarkConfig(
                baseUrl,
                host,
                port,
                dataDirectory,
                keepData,
                apiKey,
                tenantId,
                defaultFlushThresholdBytes,
                rateLimitPerMinute,
                rateLimitBurst,
                slowQueryThresholdMillis,
                preset,
                collectionPrefix,
                requestTimeout
        );
    }

    public static String usage() {
        return """
                Usage:
                  java ... -jar anaxa-benchmark.jar [--profile=standard] [--keep-data]
                
                Common options:
                  --profile=quick|standard|markdown-kb|full
                  --collection-prefix=<prefix>
                  --request-timeout-seconds=<seconds>
                
                Embedded server mode (default):
                  --host=127.0.0.1
                  --port=0
                  --data-dir=benchmark-data
                  --default-flush-threshold-bytes=67108864
                  --rate-limit-per-minute=1000000
                  --rate-limit-burst=100000
                  --slow-query-threshold-ms=1000
                  --keep-data=true|false or --keep-data
                
                Remote server mode:
                  --base-url=http://127.0.0.1:8080
                  --api-key=<optional>
                  --tenant-id=<optional>
                """;
    }

    private static String stripTrailingSlash(String value) {
        String normalized = value.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static String normalizeCollectionPrefix(String value) {
        String normalized = Objects.requireNonNull(value, "collectionPrefix")
                .trim()
                .toLowerCase()
                .replaceAll("[^a-z0-9-]+", "-")
                .replaceAll("-{2,}", "-");
        normalized = normalized.replaceAll("^-+", "").replaceAll("-+$", "");
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("collectionPrefix must contain at least one alphanumeric character");
        }
        return normalized;
    }
}
