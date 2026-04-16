package cn.suhoan.anaxa.storage;

import cn.suhoan.anaxa.common.model.CollectionDefinition;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

public record CollectionPaths(
        Path root,
        Path metadataFile,
        Path walDirectory,
        Path activeWal,
        Path segmentsDirectory,
        Path quarantineDirectory
) {
    public static CollectionPaths of(Path dataRoot, String collectionName) {
        return of(dataRoot, CollectionDefinition.DEFAULT_TENANT, collectionName);
    }

    public static CollectionPaths of(Path dataRoot, String tenantId, String collectionName) {
        String normalizedTenantId = CollectionDefinition.normalizeTenantId(tenantId);
        Path root = CollectionDefinition.DEFAULT_TENANT.equals(normalizedTenantId)
                ? dataRoot.resolve(collectionName)
                : dataRoot.resolve("tenants").resolve(normalizedTenantId).resolve("collections").resolve(collectionName);
        Path walDirectory = root.resolve("wal");
        return new CollectionPaths(
                root,
                root.resolve("collection.json"),
                walDirectory,
                walDirectory.resolve("active.wal"),
                root.resolve("segments"),
                root.resolve("quarantine")
        );
    }

    public void ensureDirectories() throws IOException {
        Files.createDirectories(root);
        Files.createDirectories(walDirectory);
        Files.createDirectories(segmentsDirectory);
        Files.createDirectories(quarantineDirectory);
    }

    public Path frozenWal(long generation) {
        return walDirectory.resolve("frozen-%020d.wal".formatted(generation));
    }

    public Path segmentFile(long generation) {
        return segmentsDirectory.resolve("segment-%020d.seg".formatted(generation));
    }

    public List<Path> listSegmentFiles() throws IOException {
        if (!Files.isDirectory(segmentsDirectory)) {
            return List.of();
        }
        try (Stream<Path> paths = Files.list(segmentsDirectory)) {
            return paths
                    .filter(path -> path.getFileName().toString().endsWith(".seg"))
                    .sorted(Comparator.comparingLong(CollectionPaths::generationFromSegmentFile))
                    .toList();
        }
    }

    public List<Path> listWalFiles() throws IOException {
        if (!Files.isDirectory(walDirectory)) {
            return List.of();
        }
        try (Stream<Path> paths = Files.list(walDirectory)) {
            return paths
                    .filter(path -> path.getFileName().toString().endsWith(".wal"))
                    .sorted(Comparator.comparing(Path::getFileName))
                    .toList();
        }
    }

    public static long generationFromSegmentFile(Path path) {
        return parseGeneration(path.getFileName().toString(), "segment-", ".seg");
    }

    public static long generationFromWalFile(Path path) {
        String fileName = path.getFileName().toString();
        if ("active.wal".equals(fileName)) {
            return 0L;
        }
        return parseGeneration(fileName, "frozen-", ".wal");
    }

    private static long parseGeneration(String fileName, String prefix, String suffix) {
        if (!fileName.startsWith(prefix) || !fileName.endsWith(suffix)) {
            throw new IllegalArgumentException("Unexpected generated file name: " + fileName);
        }
        return Long.parseLong(fileName.substring(prefix.length(), fileName.length() - suffix.length()));
    }
}
