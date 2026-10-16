package vn.edu.pbl4sync.server;

import vn.edu.pbl4sync.server.db.Database;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

public final class SnapshotStore {
    public static final long MAX_SNAPSHOT_BYTES = Database.SNAPSHOT_QUOTA_BYTES;
    private final Path root;

    public SnapshotStore(Path root) throws IOException {
        this.root = root.toAbsolutePath().normalize();
        Files.createDirectories(this.root);
    }

    public Path stage(long workspaceId, Path source) throws IOException {
        long size = Files.size(source);
        if (size > MAX_SNAPSHOT_BYTES)
            throw new IOException("File exceeds the 100 MB snapshot limit; change was not applied");

        Path workspaceDirectory = root.resolve(String.valueOf(workspaceId)).normalize();
        if (!workspaceDirectory.startsWith(root))
            throw new IOException("Invalid workspace snapshot directory");
        Files.createDirectories(workspaceDirectory);
        Path staged = workspaceDirectory.resolve(UUID.randomUUID() + ".snapshot");
        try {
            Files.copy(source, staged);
            return staged;
        } catch (IOException e) {
            Files.deleteIfExists(staged);
            throw e;
        }
    }

    public String storedPath(Path snapshot) throws IOException {
        Path normalized = snapshot.toAbsolutePath().normalize();
        if (!normalized.startsWith(root))
            throw new IOException("Snapshot is outside the configured store");
        return root.relativize(normalized).toString();
    }

    public Path resolve(String storedPath) throws IOException {
        Path resolved = root.resolve(storedPath).normalize();
        if (!resolved.startsWith(root))
            throw new IOException("Invalid snapshot path in database");
        return resolved;
    }

    public void deleteStored(String storedPath) throws IOException {
        Files.deleteIfExists(resolve(storedPath));
    }

    public void delete(Path snapshot) throws IOException {
        Path normalized = snapshot.toAbsolutePath().normalize();
        if (!normalized.startsWith(root))
            throw new IOException("Refusing to delete a file outside the snapshot store");
        Files.deleteIfExists(normalized);
    }

    public void deleteOrphans(Set<String> retainedPaths) throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                if (!retainedPaths.contains(root.relativize(path).toString()))
                    Files.deleteIfExists(path);
            }
        }
    }
}
