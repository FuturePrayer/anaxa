package cn.suhoan.anaxa.common.model;

/**
 * Request body for creating a collection backup.
 *
 * @param backupId caller-provided backup identifier
 */
public record BackupCollectionRequest(String backupId) {
    /**
     * Creates a backup request and normalizes the backup id.
     */
    public BackupCollectionRequest {
        backupId = BackupIds.normalize(backupId);
    }
}
