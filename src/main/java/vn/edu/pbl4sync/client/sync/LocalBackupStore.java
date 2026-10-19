package vn.edu.pbl4sync.client.sync;

import vn.edu.pbl4sync.common.HashUtil;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class LocalBackupStore {
    private static final Pattern BACKUP_NAME =
            Pattern.compile("^(.*)_v(\\d{20})_\\d{8}_\\d{6}_\\d{3}(\\..*)?$");

    public record BackupVersion(String path, long version) {}

    private LocalBackupStore() {}

    public static Path backupIfDifferent(Path syncRoot, long workspaceId, String workspaceName,
                                         String relativePath, Path source, long version,
                                         String currentChecksum, String incomingChecksum) throws Exception {
        if (currentChecksum == null || currentChecksum.isBlank()
                || Objects.equals(currentChecksum, incomingChecksum)) return null;
        Path backupRoot = backupRoot(syncRoot, workspaceId, workspaceName);
        Path logicalTarget = safeResolve(backupRoot, relativePath);
        String name = logicalTarget.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        String prefix = base + "_v";
        try (var files = Files.exists(logicalTarget.getParent())
                ? Files.list(logicalTarget.getParent())
                : java.util.stream.Stream.<Path>empty()) {
            for (Path existing : files.filter(Files::isRegularFile)
                    .filter(p -> isBackupFor(p, prefix, ext)).toList()) {
                if (Objects.equals(HashUtil.sha256(existing), currentChecksum)) return existing;
            }
        }
        return archiveVersion(syncRoot, workspaceId, workspaceName, relativePath,
                source, version, currentChecksum);
    }

    public static Path archiveVersion(Path syncRoot, long workspaceId, String workspaceName,
                                      String relativePath, Path source, long version,
                                      String expectedChecksum) throws Exception {
        if (expectedChecksum == null || expectedChecksum.isBlank()
                || !Files.isRegularFile(source)
                || !Objects.equals(HashUtil.sha256(source), expectedChecksum)) {
            throw new IllegalStateException("Backup source does not match the expected file version");
        }

        Path backupRoot = backupRoot(syncRoot, workspaceId, workspaceName);
        Path logicalTarget = safeResolve(backupRoot, relativePath);
        Files.createDirectories(logicalTarget.getParent());

        String name = logicalTarget.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        String versionPart = String.format(Locale.ROOT, "%020d", Math.max(0, version));
        String versionPrefix = base + "_v" + versionPart + "_";
        try (var files = Files.list(logicalTarget.getParent())) {
            for (Path existing : files.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().startsWith(versionPrefix)
                            && p.getFileName().toString().endsWith(ext)).toList()) {
                if (!Objects.equals(HashUtil.sha256(existing), expectedChecksum)) {
                    throw new IllegalStateException("A different backup already exists for version " + version);
                }
                return existing;
            }
        }

        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS"));
        Path backup = logicalTarget.resolveSibling(base + "_v" + versionPart + "_" + stamp + ext);
        Files.copy(source, backup, StandardCopyOption.COPY_ATTRIBUTES);
        if (!Objects.equals(HashUtil.sha256(backup), expectedChecksum)
                || !Objects.equals(HashUtil.sha256(source), expectedChecksum)) {
            Files.deleteIfExists(backup);
            throw new IllegalStateException("File changed while creating its backup; synchronization was stopped");
        }
        return backup;
    }

    public static List<BackupVersion> listVersions(Path syncRoot, long workspaceId,
                                                   String workspaceName) throws Exception {
        Path backupRoot = backupRoot(syncRoot, workspaceId, workspaceName);
        if (!Files.isDirectory(backupRoot)) return List.of();

        List<BackupVersion> versions = new ArrayList<>();
        try (var files = Files.walk(backupRoot)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                Matcher matcher = BACKUP_NAME.matcher(file.getFileName().toString());
                if (!matcher.matches()) continue;
                long version = Long.parseLong(matcher.group(2));
                String originalName = matcher.group(1)
                        + (matcher.group(3) == null ? "" : matcher.group(3));
                String relativePath = backupRoot.relativize(file.getParent())
                        .resolve(originalName).toString().replace('\\', '/');
                versions.add(new BackupVersion(relativePath, version));
            }
        }
        return versions;
    }

    private static Path backupRoot(Path syncRoot, long workspaceId, String workspaceName) {
        String safeWorkspace = workspaceName.replaceAll("[^\\p{L}\\p{N}._ -]", "_").trim();
        return syncRoot.resolve("BackUp")
                .resolve(workspaceId + "_" + safeWorkspace).toAbsolutePath().normalize();
    }

    private static boolean isBackupFor(Path path, String prefix, String extension) {
        String name = path.getFileName().toString();
        return name.startsWith(prefix) && name.endsWith(extension);
    }

    private static Path safeResolve(Path root, String relativePath) {
        Path resolved = root.resolve(relativePath.replace('/', java.io.File.separatorChar)).normalize();
        if (!resolved.startsWith(root)) throw new IllegalArgumentException("Unsafe backup path");
        return resolved;
    }
}
