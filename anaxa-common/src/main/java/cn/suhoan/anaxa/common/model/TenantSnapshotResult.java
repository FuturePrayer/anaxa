package cn.suhoan.anaxa.common.model;

import java.util.List;
import java.util.Objects;

public record TenantSnapshotResult(String backupId, List<BackupSummary> collections) {
    public TenantSnapshotResult {
        backupId = Objects.requireNonNull(backupId, "backupId").trim();
        if (backupId.isEmpty()) {
            throw new IllegalArgumentException("backupId must not be blank");
        }
        collections = List.copyOf(Objects.requireNonNull(collections, "collections"));
    }
}
