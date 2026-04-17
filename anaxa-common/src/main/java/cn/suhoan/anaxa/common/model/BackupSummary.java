package cn.suhoan.anaxa.common.model;

import java.time.Instant;
import java.util.Objects;

public record BackupSummary(String backupId, Instant createdAt, CollectionStats stats) {
    public BackupSummary {
        backupId = Objects.requireNonNull(backupId, "backupId").trim();
        if (backupId.isEmpty()) {
            throw new IllegalArgumentException("backupId must not be blank");
        }
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
        stats = Objects.requireNonNull(stats, "stats");
    }
}
