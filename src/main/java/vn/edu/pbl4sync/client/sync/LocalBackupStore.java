package vn.edu.pbl4sync.client.sync;

import vn.edu.pbl4sync.common.ChunkManifest;
import vn.edu.pbl4sync.common.RabinChunker;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.net.InetAddress;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.LongFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Local manifest archive with one verified full copy for the latest archived version. */
public final class LocalBackupStore {
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
            .withZone(ZoneOffset.UTC);
    private static final Pattern MANIFEST_NAME = Pattern.compile("^(.+)_v([0-9]+)_([0-9]{8}_[0-9]{6})\\.manifest$");

    private final Path syncRoot;
    private final Path backupRoot;
    private final LongFunction<String> workspaceNameResolver;

    /** Places BackUp beside the sync root so it cannot be picked up by sync scanning. */
    public LocalBackupStore(Path syncRoot) {
        this(syncRoot, defaultBackupRoot(syncRoot, null), ignored -> null);
    }

    /** Allows an explicit external backup location, rejecting a path inside syncRoot. */
    public LocalBackupStore(Path syncRoot, Path backupRoot) {
        this(syncRoot, backupRoot, ignored -> null);
    }

    /** Uses the configured device name and resolves readable workspace names by their IDs. */
    public LocalBackupStore(Path syncRoot, String deviceName, LongFunction<String> workspaceNameResolver) {
        this(syncRoot, defaultBackupRoot(syncRoot, deviceName), workspaceNameResolver);
    }

    private LocalBackupStore(Path syncRoot, Path backupRoot, LongFunction<String> workspaceNameResolver) {
        this.syncRoot = normalize(syncRoot, "syncRoot");
        this.backupRoot = normalize(backupRoot, "backupRoot");
        this.workspaceNameResolver = Objects.requireNonNull(workspaceNameResolver, "workspaceNameResolver");
        if (this.backupRoot.startsWith(this.syncRoot)) {
            throw new IllegalArgumentException("BackUp directory must be outside the sync root");
        }
    }

    /** Archives the current file as one immutable version and refreshes the latest full copy when appropriate. */
    public synchronized BackupVersion archiveVersion(long workspaceId, String relativePath,
                                                      long version, Path sourceFile) throws IOException {
        if (workspaceId <= 0 || version < 0) throw new IllegalArgumentException("Invalid workspace or version");
        String normalizedRelativePath = normalizeRelativePath(relativePath);
        Path source = normalize(sourceFile, "sourceFile");
        if (!Files.isRegularFile(source)) throw new IOException("Backup source file does not exist: " + source);

        Path directory = fileDirectory(workspaceId, normalizedRelativePath);
        Files.createDirectories(directory);
        String displayName = safeFileName(fileName(normalizedRelativePath));

        Path snapshot = Files.createTempFile(directory, ".backup-capture-", ".tmp");
        Path stagedManifest = null;
        boolean snapshotMoved = false;
        boolean manifestMoved = false;
        try {
            BasicFileAttributes before = Files.readAttributes(source, BasicFileAttributes.class);
            Files.copy(source, snapshot, StandardCopyOption.REPLACE_EXISTING);
            BasicFileAttributes after = Files.readAttributes(source, BasicFileAttributes.class);
            if (!sameFileState(before, after) || Files.mismatch(source, snapshot) != -1) {
                throw new IOException("Source file changed while it was being backed up: " + source);
            }

            long snapshotSize = Files.size(snapshot);
            ChunkManifest manifest = ChunkManifest.from(RabinChunker.chunk(snapshot), snapshotSize);
            List<BackupVersion> existing = listVersions(workspaceId, normalizedRelativePath);
            Optional<BackupVersion> sameVersion = existing.stream()
                    .filter(record -> record.version() == version)
                    .findFirst();
            if (sameVersion.isPresent()) {
                ChunkManifest prior = readManifestFile(sameVersion.get().manifestPath());
                if (!prior.serialize().equals(manifest.serialize())) {
                    throw new IOException("Version " + version + " already exists with different content");
                }
            }

            long latestVersion = existing.stream().mapToLong(BackupVersion::version).max().orElse(-1);
            boolean shouldKeepFullCopy = version >= latestVersion;
            String timestamp = TIMESTAMP.format(Instant.now());
            String stem = displayName + "_v" + version + "_" + timestamp;
            Path manifestPath = directory.resolve(stem + ".manifest");
            Path fullCopyPath = shouldKeepFullCopy ? directory.resolve(stem + ".full") : null;
            stagedManifest = Files.createTempFile(directory, ".backup-manifest-", ".tmp");
            manifest.writeTo(stagedManifest);

            moveReplacing(stagedManifest, manifestPath);
            manifestMoved = true;
            if (shouldKeepFullCopy) {
                moveReplacing(snapshot, fullCopyPath);
                snapshotMoved = true;
                removeOlderFullCopies(directory, displayName, fullCopyPath);
            }

            return new BackupVersion(workspaceId, normalizedRelativePath, version,
                    timestamp, manifestPath, fullCopyPath);
        } finally {
            if (!snapshotMoved) Files.deleteIfExists(snapshot);
            if (stagedManifest != null && !manifestMoved) Files.deleteIfExists(stagedManifest);
        }
    }

    /** Archives a version if that version is not already present; identical versions are left untouched. */
    public synchronized BackupVersion backupIfDifferent(long workspaceId, String relativePath,
                                                         long version, Path sourceFile) throws IOException {
        String normalizedRelativePath = normalizeRelativePath(relativePath);
        List<BackupVersion> versions = listVersions(workspaceId, normalizedRelativePath);
        Optional<BackupVersion> existing = versions.stream()
                .filter(record -> record.version() == version)
                .findFirst();
        if (existing.isEmpty()) return archiveVersion(workspaceId, normalizedRelativePath, version, sourceFile);

        Path source = normalize(sourceFile, "sourceFile");
        if (!Files.isRegularFile(source)) throw new IOException("Backup source file does not exist: " + source);
        ChunkManifest current = ChunkManifest.from(RabinChunker.chunk(source), Files.size(source));
        ChunkManifest archived = readManifestFile(existing.get().manifestPath());
        if (!current.serialize().equals(archived.serialize())) {
            throw new IOException("Version " + version + " already exists with different content");
        }
        if (existing.get().fullCopyPath() == null
                && versions.stream().mapToLong(BackupVersion::version).max().orElse(-1) == version) {
            return archiveVersion(workspaceId, normalizedRelativePath, version, sourceFile);
        }
        if (existing.get().fullCopyPath() != null) {
            try {
                verifyFileAgainstManifest(existing.get().fullCopyPath(), archived);
            } catch (IOException e) {
                return archiveVersion(workspaceId, normalizedRelativePath, version, sourceFile);
            }
        }
        return existing.get();
    }

    /** Returns archived versions in descending version and timestamp order. */
    public synchronized List<BackupVersion> listVersions(long workspaceId, String relativePath) throws IOException {
        if (workspaceId <= 0) throw new IllegalArgumentException("Invalid workspace id");
        String normalizedRelativePath = normalizeRelativePath(relativePath);
        Path directory = fileDirectory(workspaceId, normalizedRelativePath);
        if (!Files.exists(directory)) return List.of();
        if (!Files.isDirectory(directory)) throw new IOException("Backup path is not a directory: " + directory);

        List<BackupVersion> versions = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "*.manifest")) {
            for (Path manifestPath : stream) {
                Matcher matcher = MANIFEST_NAME.matcher(manifestPath.getFileName().toString());
                if (!matcher.matches()) throw new IOException("Invalid backup manifest filename: " + manifestPath.getFileName());
                if (!safeFileName(fileName(normalizedRelativePath)).equals(matcher.group(1))) continue;
                long version;
                try {
                    version = Long.parseLong(matcher.group(2));
                } catch (NumberFormatException e) {
                    throw new IOException("Invalid version in backup manifest filename: " + manifestPath.getFileName(), e);
                }
                readManifestFile(manifestPath);
                String stem = manifestPath.getFileName().toString().substring(0,
                        manifestPath.getFileName().toString().length() - ".manifest".length());
                Path fullCopy = directory.resolve(stem + ".full");
                versions.add(new BackupVersion(workspaceId, normalizedRelativePath, version,
                        matcher.group(3), manifestPath, Files.isRegularFile(fullCopy) ? fullCopy : null));
            }
        }
        versions.sort(Comparator.comparingLong(BackupVersion::version).reversed()
                .thenComparing(BackupVersion::createdAt, Comparator.reverseOrder()));
        return List.copyOf(versions);
    }

    /** Reads and validates the most recently archived manifest for the requested version. */
    public synchronized ChunkManifest readManifest(long workspaceId, String relativePath,
                                                    long version) throws IOException {
        if (workspaceId <= 0 || version < 0) throw new IllegalArgumentException("Invalid workspace or version");
        List<BackupVersion> versions = listVersions(workspaceId, relativePath);
        for (BackupVersion record : versions) {
            if (record.version() == version) return readManifestFile(record.manifestPath());
        }
        throw new IOException("Backup manifest not found for version " + version);
    }

    /** Restores the newest available, manifest-verified full copy while offline. */
    public synchronized BackupVersion restoreLatestOffline(long workspaceId, String relativePath,
                                                           Path destination) throws IOException {
        String normalizedRelativePath = normalizeRelativePath(relativePath);
        List<BackupVersion> versions = listVersions(workspaceId, normalizedRelativePath);
        IOException lastFailure = null;
        for (BackupVersion record : versions) {
            Path fullCopy = record.fullCopyPath();
            if (fullCopy == null || !Files.isRegularFile(fullCopy)) continue;
            try {
                ChunkManifest manifest = readManifestFile(record.manifestPath());
                verifyFileAgainstManifest(fullCopy, manifest);
                copyAtomically(fullCopy, normalize(destination, "destination"), manifest);
                return record;
            } catch (IOException e) {
                if (lastFailure == null) lastFailure = e;
                else lastFailure.addSuppressed(e);
            }
        }
        if (lastFailure != null) throw new IOException("No valid offline full copy could be restored", lastFailure);
        throw new IOException("No offline full copy exists for " + normalizedRelativePath);
    }

    /** Root of the external BackUp directory. */
    public Path backupRoot() {
        return backupRoot;
    }

    private static Path defaultBackupRoot(Path syncRoot, String configuredDeviceName) {
        Path normalized = normalize(syncRoot, "syncRoot");
        Path parent = normalized.getParent();
        if (parent == null) {
            throw new IllegalArgumentException("A volume-root sync directory needs an explicit external BackUp path");
        }
        String deviceName = firstNonBlank(configuredDeviceName,
                System.getenv("COMPUTERNAME"), localHostName(), "unknown-device");
        return parent.resolve("BackUp").resolve(safeFileSystemSegment(deviceName));
    }

    private Path fileDirectory(long workspaceId, String relativePath) {
        String workspaceName = workspaceNameResolver.apply(workspaceId);
        if (workspaceName == null || workspaceName.isBlank()) workspaceName = String.valueOf(workspaceId);
        Path directory = backupRoot.resolve("workspace-" + safeFileSystemSegment(workspaceName));
        Path relative = Path.of(relativePath);
        Path parent = relative.getParent();
        if (parent != null) {
            for (Path segment : parent) directory = directory.resolve(safeFileSystemSegment(segment.toString()));
        }
        Path normalizedDirectory = directory.normalize();
        if (!normalizedDirectory.startsWith(backupRoot)) {
            throw new IllegalArgumentException("Backup path escapes the backup root");
        }
        return normalizedDirectory;
    }

    private ChunkManifest readManifestFile(Path path) throws IOException {
        try {
            return ChunkManifest.readFrom(path);
        } catch (IllegalArgumentException e) {
            throw new IOException("Backup manifest is invalid: " + path, e);
        }
    }

    private void verifyFileAgainstManifest(Path file, ChunkManifest manifest) throws IOException {
        ChunkManifest actual = ChunkManifest.from(RabinChunker.chunk(file), Files.size(file));
        if (!actual.serialize().equals(manifest.serialize())) {
            throw new IOException("Full copy does not match its manifest: " + file);
        }
    }

    private void copyAtomically(Path source, Path destination, ChunkManifest manifest) throws IOException {
        Path absolute = destination.toAbsolutePath().normalize();
        if (absolute.startsWith(backupRoot)) throw new IOException("Restore destination cannot be inside BackUp");
        Path parent = absolute.getParent();
        if (parent == null) throw new IOException("Restore destination has no parent directory");
        Files.createDirectories(parent);
        Path staged = Files.createTempFile(parent, ".offline-restore-", ".tmp");
        boolean moved = false;
        try {
            Files.copy(source, staged, StandardCopyOption.REPLACE_EXISTING);
            verifyFileAgainstManifest(staged, manifest);
            moveReplacing(staged, absolute);
            moved = true;
        } finally {
            if (!moved) Files.deleteIfExists(staged);
        }
    }

    private void removeOlderFullCopies(Path directory, String displayName, Path keep) {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "*.full")) {
            for (Path candidate : stream) {
                String name = candidate.getFileName().toString();
                Matcher matcher = name.endsWith(".full")
                        ? MANIFEST_NAME.matcher(name.substring(0, name.length() - ".full".length()) + ".manifest")
                        : MANIFEST_NAME.matcher("");
                if (!candidate.equals(keep) && matcher.matches() && displayName.equals(matcher.group(1))) {
                    try { Files.deleteIfExists(candidate); } catch (IOException ignored) { }
                }
            }
        } catch (IOException ignored) {
            // Retaining an older full copy is safer than failing an already completed archive.
        }
    }

    private boolean sameFileState(BasicFileAttributes first, BasicFileAttributes second) {
        return first.size() == second.size()
                && first.lastModifiedTime().equals(second.lastModifiedTime())
                && (first.fileKey() == null || Objects.equals(first.fileKey(), second.fileKey()));
    }

    private void moveReplacing(Path source, Path destination) throws IOException {
        try {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private String normalizeRelativePath(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) throw new IllegalArgumentException("Relative path is required");
        String normalized = relativePath.replace('\\', '/');
        if (normalized.startsWith("/") || normalized.matches("^[A-Za-z]:.*")) {
            throw new IllegalArgumentException("Backup path must be relative");
        }
        for (String segment : normalized.split("/", -1)) {
            if (segment.equals("..") || segment.isBlank()) throw new IllegalArgumentException("Invalid backup path");
        }
        String canonical = Path.of(normalized).normalize().toString().replace('\\', '/');
        if (canonical.isBlank() || canonical.equals("..") || canonical.startsWith("../")) {
            throw new IllegalArgumentException("Invalid backup path");
        }
        return canonical;
    }

    private String safeFileName(String fileName) {
        return safeFileSystemSegment(fileName);
    }

    private String fileName(String relativePath) {
        Path name = Path.of(relativePath).getFileName();
        return name == null ? "file" : name.toString();
    }

    private static String safeFileSystemSegment(String value) {
        String safe = value.replaceAll("[^\\p{L}\\p{N}._ -]", "_").trim();
        while (safe.endsWith(".")) safe = safe.substring(0, safe.length() - 1);
        if (safe.isBlank() || safe.equals(".") || safe.equals("..")) safe = "item";
        String stem = safe.contains(".") ? safe.substring(0, safe.indexOf('.')) : safe;
        if (stem.matches("(?i)CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9]")) safe = "_" + safe;
        return safe;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value;
        return "unknown-device";
    }

    private static String localHostName() {
        try { return InetAddress.getLocalHost().getHostName(); }
        catch (Exception ignored) { return null; }
    }

    private static Path normalize(Path path, String label) {
        return Objects.requireNonNull(path, label).toAbsolutePath().normalize();
    }

    public record BackupVersion(long workspaceId, String relativePath, long version, String createdAt,
                                Path manifestPath, Path fullCopyPath) { }
}
