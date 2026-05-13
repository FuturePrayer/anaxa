package cn.suhoan.anaxa.common.model;

public record BackupCollectionRequest(String backupId) {
    public BackupCollectionRequest {
        backupId = BackupIds.normalize(backupId);
    }
}
