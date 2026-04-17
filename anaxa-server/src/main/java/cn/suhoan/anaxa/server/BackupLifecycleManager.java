package cn.suhoan.anaxa.server;

import cn.suhoan.anaxa.common.model.BackupSummary;
import cn.suhoan.anaxa.common.model.CollectionStats;
import cn.suhoan.anaxa.common.model.TenantSnapshotResult;
import cn.suhoan.anaxa.engine.VectorDatabaseEngine;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

final class BackupLifecycleManager implements AutoCloseable {
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC);

    private final VectorDatabaseEngine engine;
    private final Path backupDirectory;
    private final long snapshotIntervalSeconds;
    private final int autoSnapshotRetentionPerCollection;
    private final MetricsRegistry metrics;
    private final ScheduledExecutorService scheduler;
    private final ConcurrentHashMap<String, SnapshotFingerprint> lastSnapshots;
    private final AtomicBoolean started;

    BackupLifecycleManager(
            VectorDatabaseEngine engine,
            Path backupDirectory,
            long snapshotIntervalSeconds,
            int autoSnapshotRetentionPerCollection,
            MetricsRegistry metrics
    ) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.backupDirectory = Objects.requireNonNull(backupDirectory, "backupDirectory");
        this.snapshotIntervalSeconds = snapshotIntervalSeconds;
        this.autoSnapshotRetentionPerCollection = autoSnapshotRetentionPerCollection;
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.scheduler = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("anaxa-snapshot-", 0).factory());
        this.lastSnapshots = new ConcurrentHashMap<>();
        this.started = new AtomicBoolean(false);
        seedLastSnapshots();
    }

    void start() {
        if (snapshotIntervalSeconds <= 0L || !started.compareAndSet(false, true)) {
            return;
        }
        scheduler.scheduleWithFixedDelay(this::runScheduledCycle, snapshotIntervalSeconds, snapshotIntervalSeconds, TimeUnit.SECONDS);
    }

    List<BackupSummary> listBackups(String tenantId) {
        return tenantId == null
                ? BackupCatalog.listBackups(engine, backupDirectory)
                : BackupCatalog.listBackups(engine, backupDirectory, tenantId);
    }

    TenantSnapshotResult snapshotTenant(String tenantId, String requestedBackupId) {
        String backupId = requestedBackupId == null || requestedBackupId.isBlank()
                ? "manual-%s-%s".formatted(tenantId, TIMESTAMP_FORMAT.format(Instant.now()))
                : requestedBackupId.trim();
        List<CollectionStats> collections = engine.listCollections(tenantId);
        if (collections.isEmpty()) {
            return new TenantSnapshotResult(backupId, List.of());
        }
        ArrayList<BackupSummary> completed = new ArrayList<>(collections.size());
        try {
            for (CollectionStats stats : collections.stream().sorted(Comparator.comparing(CollectionStats::name)).toList()) {
                completed.add(snapshotCollection(stats, backupId, false));
            }
            return new TenantSnapshotResult(backupId, List.copyOf(completed));
        } catch (RuntimeException exception) {
            try {
                BackupCatalog.deleteBackup(backupDirectory, backupId);
            } catch (IOException cleanupException) {
                exception.addSuppressed(cleanupException);
            }
            throw exception;
        }
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
        try {
            scheduler.awaitTermination(5L, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while closing backup lifecycle manager", exception);
        }
    }

    private void runScheduledCycle() {
        try {
            for (CollectionStats stats : engine.listCollections()) {
                if (shouldSnapshot(stats)) {
                    BackupSummary summary = snapshotCollection(stats, autoBackupId(stats), true);
                    pruneAutoBackups(stats);
                    lastSnapshots.put(collectionKey(stats), SnapshotFingerprint.from(summary.stats()));
                }
            }
        } catch (RuntimeException exception) {
            // Scheduled lifecycle work must not terminate the scheduler thread.
        }
    }

    private boolean shouldSnapshot(CollectionStats stats) {
        if (stats.flushInProgress() || stats.compactionInProgress()) {
            return false;
        }
        if (stats.liveVectorCount() == 0L && stats.tombstoneCount() == 0L && stats.segmentCount() == 0 && stats.storageBytes() == 0L) {
            return false;
        }
        SnapshotFingerprint current = SnapshotFingerprint.from(stats);
        SnapshotFingerprint previous = lastSnapshots.get(collectionKey(stats));
        return !current.equals(previous);
    }

    private BackupSummary snapshotCollection(CollectionStats stats, String backupId, boolean automatic) {
        long startedAtNanos = System.nanoTime();
        boolean success = false;
        try {
            engine.flush(stats.tenantId(), stats.name());
            CollectionStats latest = engine.backupCollection(stats.tenantId(), stats.name(), backupId, backupDirectory);
            lastSnapshots.put(collectionKey(latest), SnapshotFingerprint.from(latest));
            success = true;
            return new BackupSummary(backupId, Instant.now(), latest);
        } finally {
            metrics.recordSnapshot(stats.tenantId(), stats.name(), System.nanoTime() - startedAtNanos, success, automatic);
        }
    }

    private void pruneAutoBackups(CollectionStats stats) {
        if (autoSnapshotRetentionPerCollection <= 0) {
            return;
        }
        List<BackupSummary> candidates = BackupCatalog.listBackups(engine, backupDirectory, stats.tenantId()).stream()
                .filter(summary -> summary.stats().name().equals(stats.name()))
                .filter(summary -> summary.backupId().startsWith(autoBackupPrefix(stats)))
                .sorted(Comparator.comparing(BackupSummary::createdAt).reversed())
                .toList();
        for (int index = autoSnapshotRetentionPerCollection; index < candidates.size(); index++) {
            BackupSummary expired = candidates.get(index);
            try {
                BackupCatalog.deleteBackup(backupDirectory, expired.backupId());
                metrics.recordSnapshotRetentionDelete(expired.stats().tenantId(), expired.stats().name());
            } catch (IOException exception) {
                throw new IllegalStateException("Failed to delete expired backup " + expired.backupId(), exception);
            }
        }
    }

    private void seedLastSnapshots() {
        for (BackupSummary summary : BackupCatalog.listBackups(engine, backupDirectory)) {
            if (!summary.backupId().startsWith(autoBackupPrefix(summary.stats()))) {
                continue;
            }
            lastSnapshots.merge(
                    collectionKey(summary.stats()),
                    SnapshotFingerprint.from(summary.stats(), summary.createdAt()),
                    (left, right) -> right.createdAt().isAfter(left.createdAt()) ? right : left
            );
        }
    }

    private String autoBackupId(CollectionStats stats) {
        return autoBackupPrefix(stats) + TIMESTAMP_FORMAT.format(Instant.now());
    }

    private static String autoBackupPrefix(CollectionStats stats) {
        return autoBackupPrefix(stats.tenantId(), stats.name());
    }

    private static String autoBackupPrefix(String tenantId, String collectionName) {
        return "auto-%s-%s-".formatted(tenantId, collectionName);
    }

    private static String collectionKey(CollectionStats stats) {
        return collectionKey(stats.tenantId(), stats.name());
    }

    private static String collectionKey(String tenantId, String collectionName) {
        return tenantId + "/" + collectionName;
    }

    private record SnapshotFingerprint(
            long liveVectorCount,
            long tombstoneCount,
            int segmentCount,
            long storageBytes,
            Instant createdAt
    ) {
        private static SnapshotFingerprint from(CollectionStats stats) {
            return from(stats, Instant.now());
        }

        private static SnapshotFingerprint from(CollectionStats stats, Instant createdAt) {
            return new SnapshotFingerprint(
                    stats.liveVectorCount(),
                    stats.tombstoneCount(),
                    stats.segmentCount(),
                    stats.storageBytes(),
                    createdAt
            );
        }
    }
}
