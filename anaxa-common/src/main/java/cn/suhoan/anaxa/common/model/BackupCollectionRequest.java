package cn.suhoan.anaxa.common.model;

import java.util.Objects;

public record BackupCollectionRequest(String backupId) {
    public BackupCollectionRequest {
        backupId = Objects.requireNonNull(backupId, "backupId").trim();
        if (backupId.isEmpty()) {
            throw new IllegalArgumentException("backupId must not be blank");
        }
    }
}
