package cn.suhoan.anaxa.common.model;

import java.util.Objects;
import java.util.regex.Pattern;

public final class BackupIds {
    private static final Pattern BACKUP_ID_PATTERN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,127}");

    private BackupIds() {
    }

    public static String normalize(String backupId) {
        String normalized = Objects.requireNonNull(backupId, "backupId").trim();
        if (!BACKUP_ID_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException("backupId must match [A-Za-z0-9][A-Za-z0-9_-]{0,127}");
        }
        return normalized;
    }
}
