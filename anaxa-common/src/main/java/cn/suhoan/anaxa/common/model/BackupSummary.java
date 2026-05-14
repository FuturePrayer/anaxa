package cn.suhoan.anaxa.common.model;

import java.time.Instant;
import java.util.Objects;

/**
 * Metadata describing a persisted collection backup.
 *
 * @param backupId backup identifier
 * @param createdAt creation timestamp
 * @param stats collection statistics captured with the backup
 */
public record BackupSummary(String backupId, Instant createdAt, CollectionStats stats) {
    /**
     * Creates backup metadata.
     */
    public BackupSummary {
        backupId = Objects.requireNonNull(backupId, "backupId").trim();
        if (backupId.isEmpty()) {
            throw new IllegalArgumentException("backupId must not be blank");
        }
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
        stats = Objects.requireNonNull(stats, "stats");
    }
}
