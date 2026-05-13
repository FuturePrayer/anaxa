package cn.suhoan.anaxa.server;

import cn.suhoan.anaxa.common.model.BackupSummary;
import cn.suhoan.anaxa.common.model.BackupIds;
import cn.suhoan.anaxa.common.model.CollectionDefinition;
import cn.suhoan.anaxa.common.model.CollectionStats;
import cn.suhoan.anaxa.engine.VectorDatabaseEngine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

final class BackupCatalog {
    private BackupCatalog() {
    }

    static List<BackupSummary> listBackups(VectorDatabaseEngine engine, Path backupDirectory) {
        return listBackups(engine, backupDirectory, null, null);
    }

    static List<BackupSummary> listBackups(VectorDatabaseEngine engine, Path backupDirectory, String tenantId) {
        return listBackups(engine, backupDirectory, tenantId, null);
    }

    static List<BackupSummary> listBackups(
            VectorDatabaseEngine engine,
            Path backupDirectory,
            String tenantId,
            String backupIdFilter
    ) {
        if (!Files.isDirectory(backupDirectory)) {
            return List.of();
        }
        String normalizedTenantId = tenantId == null ? null : CollectionDefinition.normalizeTenantId(tenantId);
        ArrayList<BackupSummary> summaries = new ArrayList<>();
        try (Stream<Path> backupRoots = Files.list(backupDirectory)) {
            for (Path backupRoot : backupRoots.filter(Files::isDirectory).toList()) {
                String backupId = backupRoot.getFileName().toString();
                if (backupIdFilter != null && !backupId.equals(backupIdFilter)) {
                    continue;
                }
                Instant createdAt = Files.getLastModifiedTime(backupRoot).toInstant();
                collectDefaultTenantCollections(engine, backupDirectory, backupId, createdAt, normalizedTenantId, summaries, backupRoot);
                collectTenantCollections(engine, backupDirectory, backupId, createdAt, normalizedTenantId, summaries, backupRoot);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to list backups from " + backupDirectory, exception);
        }
        summaries.sort(Comparator.comparing(BackupSummary::createdAt).reversed()
                .thenComparing(summary -> summary.stats().tenantId())
                .thenComparing(summary -> summary.stats().name()));
        return List.copyOf(summaries);
    }

    static void deleteBackup(Path backupDirectory, String backupId) throws IOException {
        deleteRecursively(backupDirectory.resolve(BackupIds.normalize(backupId)));
    }

    private static void collectDefaultTenantCollections(
            VectorDatabaseEngine engine,
            Path backupDirectory,
            String backupId,
            Instant createdAt,
            String tenantId,
            List<BackupSummary> summaries,
            Path backupRoot
    ) throws IOException {
        try (Stream<Path> children = Files.list(backupRoot)) {
            for (Path child : children.filter(Files::isDirectory).toList()) {
                if ("tenants".equals(child.getFileName().toString()) || !isCollectionDirectory(child)) {
                    continue;
                }
                addSummary(
                        engine,
                        backupDirectory,
                        backupId,
                        createdAt,
                        CollectionDefinition.DEFAULT_TENANT,
                        tenantId,
                        child.getFileName().toString(),
                        summaries
                );
            }
        }
    }

    private static void collectTenantCollections(
            VectorDatabaseEngine engine,
            Path backupDirectory,
            String backupId,
            Instant createdAt,
            String tenantId,
            List<BackupSummary> summaries,
            Path backupRoot
    ) throws IOException {
        Path tenantsRoot = backupRoot.resolve("tenants");
        if (!Files.isDirectory(tenantsRoot)) {
            return;
        }
        try (Stream<Path> tenantDirectories = Files.list(tenantsRoot)) {
            for (Path tenantDirectory : tenantDirectories.filter(Files::isDirectory).toList()) {
                String tenantDirectoryId = tenantDirectory.getFileName().toString();
                if (tenantId != null && !tenantId.equals(tenantDirectoryId)) {
                    continue;
                }
                Path collectionsRoot = tenantDirectory.resolve("collections");
                if (!Files.isDirectory(collectionsRoot)) {
                    continue;
                }
                try (Stream<Path> collections = Files.list(collectionsRoot)) {
                    for (Path collectionDirectory : collections.filter(Files::isDirectory).toList()) {
                        if (!isCollectionDirectory(collectionDirectory)) {
                            continue;
                        }
                        addSummary(
                                engine,
                                backupDirectory,
                                backupId,
                                createdAt,
                                tenantDirectoryId,
                                tenantId,
                                collectionDirectory.getFileName().toString(),
                                summaries
                        );
                    }
                }
            }
        }
    }

    private static void addSummary(
            VectorDatabaseEngine engine,
            Path backupDirectory,
            String backupId,
            Instant createdAt,
            String candidateTenantId,
            String tenantFilter,
            String collectionName,
            List<BackupSummary> summaries
    ) {
        if (tenantFilter != null && !tenantFilter.equals(candidateTenantId)) {
            return;
        }
        CollectionStats stats = engine.previewBackupCollection(candidateTenantId, collectionName, backupId, backupDirectory);
        summaries.add(new BackupSummary(backupId, createdAt, stats));
    }

    private static boolean isCollectionDirectory(Path directory) {
        return Files.exists(directory.resolve("collection.json"));
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        if (Files.isDirectory(path)) {
            try (Stream<Path> children = Files.list(path)) {
                for (Path child : children.toList()) {
                    deleteRecursively(child);
                }
            }
        }
        Files.deleteIfExists(path);
    }
}
