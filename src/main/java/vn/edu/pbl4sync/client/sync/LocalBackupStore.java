package vn.edu.pbl4sync.client.sync;

import vn.edu.pbl4sync.common.ChunkManifest;
import vn.edu.pbl4sync.common.HashUtil;
import vn.edu.pbl4sync.common.RabinChunker;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lưu trữ backup local dạng manifest thay vì full copy.
 *
 * <p>Thay đổi so với cũ:
 * <ul>
 *   <li>Trước: copy toàn bộ file vào BackUp/ → file 50MB × 10 version = 500MB
 *   <li>Sau: chỉ lưu file manifest nhỏ (.manifest) chứa danh sách chunk hash
 *       → mỗi version chỉ tốn vài KB, nội dung thật lấy từ chunk store trên server
 * </ul>
 *
 * <p>Tên file manifest: {@code report_v00000000000000000003_20261106_084532_123.manifest}
 */
public final class LocalBackupStore {

    /** Match tên file manifest: base_vVERSION_STAMP.manifest */
    private static final Pattern BACKUP_NAME =
            Pattern.compile("^(.*)_v(\\d{20})_\\d{8}_\\d{6}_\\d{3}\\.manifest$");

    public record BackupVersion(String path, long version) {}

    private LocalBackupStore() {}

    // -----------------------------------------------------------------------
    // 1. Lưu manifest nếu version thay đổi
    // -----------------------------------------------------------------------

    /**
     * Lưu manifest của version hiện tại nếu checksum khác version sắp đến.
     * Gọi trước khi overwrite file local bằng bản mới từ server.
     *
     * @param source          file local hiện tại (sắp bị overwrite)
     * @param currentChecksum checksum của file local hiện tại
     * @param incomingChecksum checksum của bản mới sắp ghi đè
     * @return path file manifest đã lưu, hoặc null nếu không cần backup
     */
    public static Path backupIfDifferent(Path syncRoot, long workspaceId, String workspaceName,
                                         String relativePath, Path source, long version,
                                         String currentChecksum, String incomingChecksum) throws Exception {
        if (currentChecksum == null || currentChecksum.isBlank()
                || Objects.equals(currentChecksum, incomingChecksum)) return null;
        if (!Files.isRegularFile(source)) return null;

        // Kiểm tra đã có manifest cho version này chưa
        Path backupRoot = backupRoot(syncRoot, workspaceId, workspaceName);
        Path logicalTarget = safeResolve(backupRoot, relativePath);
        String name = logicalTarget.getFileName().toString();
        // Bỏ .manifest extension nếu có để lấy base name
        String base = name.endsWith(".manifest") ? name.substring(0, name.length() - 9) : name;
        int dot = base.lastIndexOf('.');
        String baseName = dot > 0 ? base.substring(0, dot) : base;
        String versionPart = String.format(Locale.ROOT, "%020d", Math.max(0, version));
        String versionPrefix = baseName + "_v" + versionPart + "_";

        if (Files.exists(logicalTarget.getParent())) {
            try (var files = Files.list(logicalTarget.getParent())) {
                for (Path existing : files.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().startsWith(versionPrefix)
                                && p.getFileName().toString().endsWith(".manifest"))
                        .toList()) {
                    // Manifest đã tồn tại cho version này
                    return existing;
                }
            }
        }

        return archiveVersion(syncRoot, workspaceId, workspaceName,
                relativePath, source, version, currentChecksum);
    }

    /**
     * Chunk file và lưu manifest của version này vào thư mục BackUp/.
     * Không copy nội dung file — chỉ lưu danh sách chunk hash (~vài KB).
     *
     * @param source          file cần backup
     * @param version         số version
     * @param expectedChecksum SHA-256 của file để xác minh
     * @return path file manifest đã lưu
     */
    public static Path archiveVersion(Path syncRoot, long workspaceId, String workspaceName,
                                      String relativePath, Path source, long version,
                                      String expectedChecksum) throws Exception {
        if (expectedChecksum == null || expectedChecksum.isBlank()
                || !Files.isRegularFile(source)) {
            throw new IllegalStateException("Backup source is missing or invalid");
        }
        // Xác minh checksum trước khi chunk
        String actualChecksum = HashUtil.sha256(source);
        if (!Objects.equals(actualChecksum, expectedChecksum)) {
            throw new IllegalStateException(
                    "Backup source checksum mismatch: expected " + expectedChecksum
                            + ", got " + actualChecksum);
        }

        // Chunk file bằng Rabin CDC
        List<RabinChunker.Chunk> chunks = RabinChunker.chunk(source);
        long fileSize = Files.size(source);
        ChunkManifest manifest = ChunkManifest.from(chunks, fileSize);

        // Lưu manifest ra đĩa
        Path backupRoot = backupRoot(syncRoot, workspaceId, workspaceName);
        Path logicalTarget = safeResolve(backupRoot, relativePath);
        Files.createDirectories(logicalTarget.getParent());

        String name = logicalTarget.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String baseName = dot > 0 ? name.substring(0, dot) : name;
        String versionPart = String.format(Locale.ROOT, "%020d", Math.max(0, version));
        String stamp = LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS"));

        // Tên file: report_v00000000000000000003_20261106_084532_123.manifest
        Path manifestPath = logicalTarget.resolveSibling(
                baseName + "_v" + versionPart + "_" + stamp + ".manifest");
        manifest.writeTo(manifestPath);
        return manifestPath;
    }

    // -----------------------------------------------------------------------
    // 2. Liệt kê các version backup
    // -----------------------------------------------------------------------

    /**
     * Liệt kê tất cả version backup (manifest files) trong thư mục BackUp/.
     */
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
                // Tên file gốc = base name không có _vVERSION_STAMP.manifest
                String originalName = matcher.group(1);
                // Lấy extension từ tên file gốc nếu có
                String ext = "";
                int dot = originalName.lastIndexOf('.');
                if (dot > 0) {
                    ext = originalName.substring(dot);
                    originalName = originalName.substring(0, dot);
                }
                String relativePath = backupRoot.relativize(file.getParent())
                        .resolve(originalName + ext).toString().replace('\\', '/');
                versions.add(new BackupVersion(relativePath, version));
            }
        }
        return versions;
    }

    // -----------------------------------------------------------------------
    // 3. Đọc manifest để restore (dùng khi rollback offline)
    // -----------------------------------------------------------------------

    /**
     * Đọc manifest của một version backup cụ thể.
     * Dùng khi cần restore file từ chunk store mà không có kết nối server.
     *
     * @param manifestPath path đến file .manifest
     * @return ChunkManifest đã parse
     */
    public static ChunkManifest readManifest(Path manifestPath) throws Exception {
        return ChunkManifest.readFrom(manifestPath);
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    private static Path backupRoot(Path syncRoot, long workspaceId, String workspaceName) {
        String safeWorkspace = workspaceName.replaceAll("[^\\p{L}\\p{N}._ -]", "_").trim();
        return syncRoot.resolve("BackUp")
                .resolve(workspaceId + "_" + safeWorkspace).toAbsolutePath().normalize();
    }

    private static Path safeResolve(Path root, String relativePath) {
        Path resolved = root.resolve(
                relativePath.replace('/', java.io.File.separatorChar)).normalize();
        if (!resolved.startsWith(root))
            throw new IllegalArgumentException("Unsafe backup path: " + relativePath);
        return resolved;
    }
}