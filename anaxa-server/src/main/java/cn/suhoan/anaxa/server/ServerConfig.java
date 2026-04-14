package cn.suhoan.anaxa.server;

import cn.suhoan.anaxa.engine.VectorDatabaseEngine;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;

public record ServerConfig(String host, int port, Path dataDirectory, long defaultFlushThresholdBytes) {
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
    }

    public static ServerConfig fromArgs(String[] args) {
        String host = "0.0.0.0";
        int port = 8080;
        Path dataDirectory = Paths.get("data");
        long flushThresholdBytes = VectorDatabaseEngine.DEFAULT_FLUSH_THRESHOLD_BYTES;

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
                default -> throw new IllegalArgumentException("Unknown argument: --" + key);
            }
        }

        return new ServerConfig(host, port, dataDirectory, flushThresholdBytes);
    }
}
