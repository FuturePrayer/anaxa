package cn.suhoan.anaxa.server;

import cn.suhoan.anaxa.common.json.JsonSupport;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;

final class AuditLogger {
    private final Path logPath;

    AuditLogger(Path logPath) throws IOException {
        this.logPath = logPath;
        if (logPath != null) {
            if (logPath.getParent() != null) {
                Files.createDirectories(logPath.getParent());
            }
            if (!Files.exists(logPath)) {
                Files.createFile(logPath);
            }
        }
    }

    synchronized void log(
            Instant timestamp,
            String traceId,
            AuthenticatedPrincipal principal,
            String method,
            String route,
            int statusCode,
            String remoteAddress
    ) {
        if (logPath == null) {
            return;
        }
        LinkedHashMap<String, Object> record = new LinkedHashMap<>();
        record.put("timestamp", timestamp.toString());
        record.put("traceId", traceId);
        record.put("principal", principal.id());
        record.put("roles", principal.roles().stream().map(Enum::name).sorted().toList());
        record.put("method", method);
        record.put("route", route);
        record.put("statusCode", statusCode);
        record.put("remoteAddress", remoteAddress);
        try {
            if (logPath.getParent() != null) {
                Files.createDirectories(logPath.getParent());
            }
            Files.writeString(
                    logPath,
                    JsonSupport.writeString(record) + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.APPEND
            );
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to append audit log " + logPath, exception);
        }
    }
}
