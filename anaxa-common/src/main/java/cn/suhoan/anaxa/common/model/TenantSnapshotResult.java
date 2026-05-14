package cn.suhoan.anaxa.common.model;

import java.util.List;
import java.util.Objects;

/**
 * Result of snapshotting all collections for a tenant.
 *
 * @param backupId tenant-level backup id
 * @param collections collection backups created by the snapshot
 */
public record TenantSnapshotResult(String backupId, List<BackupSummary> collections) {
    /**
     * Creates a tenant snapshot result.
     */
    public TenantSnapshotResult {
        backupId = Objects.requireNonNull(backupId, "backupId").trim();
        if (backupId.isEmpty()) {
            throw new IllegalArgumentException("backupId must not be blank");
        }
        collections = List.copyOf(Objects.requireNonNull(collections, "collections"));
    }
}
