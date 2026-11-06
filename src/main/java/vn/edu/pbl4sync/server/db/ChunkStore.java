package vn.edu.pbl4sync.server.db;

import vn.edu.pbl4sync.common.ChunkManifest;

import java.sql.*;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * CRUD cho 2 bảng chunk: {@code chunks} và {@code file_version_chunks}.
 *
 * <p>Mỗi method mượn connection từ pool, dùng xong trả lại — không giữ
 * connection lâu, an toàn khi gọi từ nhiều thread cùng lúc.
 */
public final class ChunkStore {

    private final SimpleConnectionPool pool;

    public ChunkStore(SimpleConnectionPool pool) {
        this.pool = pool;
    }

    // -----------------------------------------------------------------------
    // 1. Kiểm tra chunk nào đã có — dùng cho Delta Upload
    // -----------------------------------------------------------------------

    /**
     * Từ danh sách hash, trả về tập hash CHƯA có trong bảng {@code chunks}.
     *
     * <p>Client gửi manifest (toàn bộ hash của version mới), server gọi
     * method này để biết hash nào còn thiếu, rồi yêu cầu client upload
     * đúng những chunk đó — bỏ qua chunk đã có (deduplication).
     *
     * @param hashes danh sách SHA-256 hex cần kiểm tra
     * @return tập hash chưa tồn tại trong chunk store
     */
    public Set<String> missingHashes(List<String> hashes) throws SQLException {
        if (hashes == null || hashes.isEmpty()) return Set.of();

        Set<String> missing = new HashSet<>(hashes);
        Connection c = pool.borrow();
        try {
            // Dùng IN clause — chia batch 500 để tránh giới hạn SQL
            List<String> found = new ArrayList<>();
            for (int start = 0; start < hashes.size(); start += 500) {
                List<String> batch = hashes.subList(start,
                        Math.min(start + 500, hashes.size()));
                String placeholders = "?,".repeat(batch.size());
                placeholders = placeholders.substring(0, placeholders.length() - 1);
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT hash FROM chunks WHERE hash IN (" + placeholders + ")")) {
                    for (int i = 0; i < batch.size(); i++)
                        ps.setString(i + 1, batch.get(i));
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) found.add(rs.getString("hash"));
                    }
                }
            }
            missing.removeAll(found);
            return missing;
        } finally {
            pool.release(c);
        }
    }

    // -----------------------------------------------------------------------
    // 2. Lưu chunk mới — dùng khi client upload
    // -----------------------------------------------------------------------

    /**
     * Lưu một chunk vào bảng {@code chunks}.
     * Nếu hash đã tồn tại thì bỏ qua (INSERT IGNORE) — deduplication tự động.
     *
     * @param hash    SHA-256 hex của chunk
     * @param size    kích thước chunk (bytes)
     * @param content nội dung chunk
     */
    public void saveChunk(String hash, int size, byte[] content) throws SQLException {
        Connection c = pool.borrow();
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT IGNORE INTO chunks(hash, size, content) VALUES(?, ?, ?)")) {
            ps.setString(1, hash);
            ps.setInt(2, size);
            ps.setBytes(3, content);
            ps.executeUpdate();
        } finally {
            pool.release(c);
        }
    }

    // -----------------------------------------------------------------------
    // 3. Lưu manifest của một version — dùng sau khi nhận đủ chunk
    // -----------------------------------------------------------------------

    /**
     * Lưu danh sách chunk theo thứ tự cho một version file vào
     * bảng {@code file_version_chunks}.
     *
     * <p>Gọi method này SAU KHI đã lưu đủ tất cả chunk vào bảng {@code chunks}.
     *
     * @param fileId   ID của file trong bảng {@code files}
     * @param version  số version
     * @param manifest manifest của version này
     */
    public void saveVersionChunks(long fileId, long version,
                                  ChunkManifest manifest) throws SQLException {
        Connection c = pool.borrow();
        try {
            c.setAutoCommit(false);
            // Xóa mapping cũ (nếu có) trước khi ghi mới
            try (PreparedStatement del = c.prepareStatement(
                    "DELETE FROM file_version_chunks WHERE file_id=? AND version=?")) {
                del.setLong(1, fileId);
                del.setLong(2, version);
                del.executeUpdate();
            }
            // Ghi từng chunk theo thứ tự
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO file_version_chunks(file_id,version,chunk_order,chunk_hash,chunk_size)"
                            + " VALUES(?,?,?,?,?)")) {
                int order = 0;
                for (ChunkManifest.Entry e : manifest.entries()) {
                    ps.setLong(1, fileId);
                    ps.setLong(2, version);
                    ps.setInt(3, order++);
                    ps.setString(4, e.hash());
                    ps.setInt(5, e.length());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            c.commit();
        } catch (SQLException e) {
            try { c.rollback(); } catch (SQLException ignored) {}
            throw e;
        } finally {
            try { c.setAutoCommit(true); } catch (SQLException ignored) {}
            pool.release(c);
        }
    }

    // -----------------------------------------------------------------------
    // 4. Đọc nội dung chunk — dùng khi assemble file (download / rollback)
    // -----------------------------------------------------------------------

    /**
     * Đọc nội dung một chunk từ bảng {@code chunks}.
     *
     * @param hash SHA-256 hex của chunk
     * @return nội dung chunk, hoặc {@code null} nếu không tìm thấy
     */
    public byte[] loadChunk(String hash) throws SQLException {
        Connection c = pool.borrow();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT content FROM chunks WHERE hash=?")) {
            ps.setString(1, hash);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return rs.getBytes("content");
            }
        } finally {
            pool.release(c);
        }
    }

    // -----------------------------------------------------------------------
    // 5. Tái tạo ChunkManifest từ DB — dùng khi rollback
    // -----------------------------------------------------------------------

    /**
     * Đọc bảng {@code file_version_chunks} và tái tạo lại {@link ChunkManifest}
     * của một version cụ thể.
     *
     * <p>Rollback chỉ cần gọi method này rồi gọi
     * {@code manifest.assembleTo(dest, hash -> loadChunk(hash))} —
     * không cần đi qua version trung gian nào.
     *
     * @param fileId  ID file
     * @param version số version cần lấy manifest
     * @param totalSize kích thước file gốc (lấy từ bảng {@code file_versions})
     * @return manifest của version đó, hoặc {@code null} nếu không có chunk nào
     */
    public ChunkManifest loadManifest(long fileId, long version,
                                      long totalSize) throws SQLException {
        Connection c = pool.borrow();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT chunk_hash, chunk_size FROM file_version_chunks"
                        + " WHERE file_id=? AND version=?"
                        + " ORDER BY chunk_order ASC")) {
            ps.setLong(1, fileId);
            ps.setLong(2, version);
            try (ResultSet rs = ps.executeQuery()) {
                List<ChunkManifest.Entry> entries = new ArrayList<>();
                while (rs.next()) {
                    entries.add(new ChunkManifest.Entry(
                            rs.getString("chunk_hash"),
                            rs.getInt("chunk_size")));
                }
                if (entries.isEmpty()) return null;
                return new ChunkManifest(totalSize, entries);
            }
        } finally {
            pool.release(c);
        }
    }

    // -----------------------------------------------------------------------
    // 6. Dọn dẹp chunk mồ côi — chunk không còn được version nào dùng
    // -----------------------------------------------------------------------

    /**
     * Xóa các chunk không còn được bất kỳ version nào tham chiếu.
     *
     * <p>Gọi định kỳ (ví dụ mỗi đêm) để thu hồi dung lượng sau khi
     * xóa file hoặc xóa version cũ.
     *
     * @return số chunk đã xóa
     */
    public int purgeOrphanChunks() throws SQLException {
        Connection c = pool.borrow();
        try (PreparedStatement ps = c.prepareStatement(
                "DELETE FROM chunks WHERE hash NOT IN"
                        + " (SELECT DISTINCT chunk_hash FROM file_version_chunks)")) {
            return ps.executeUpdate();
        } finally {
            pool.release(c);
        }
    }
}