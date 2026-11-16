package vn.edu.pbl4sync.server.db;

import vn.edu.pbl4sync.common.ChunkManifest;
import vn.edu.pbl4sync.common.RabinChunker;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.HexFormat;
import java.util.regex.Pattern;

/** Database access for deduplicated, SHA-256-addressed file chunks. */
public final class ChunkStore {
    private static final int SQL_BATCH_SIZE = 500;
    private static final Pattern SHA256 = Pattern.compile("[0-9a-fA-F]{64}");
    private static final HexFormat HEX = HexFormat.of();

    private final SimpleConnectionPool pool;

    /** Uses the same connection pool as the owning Database instance. */
    public ChunkStore(Database database) {
        this.pool = Objects.requireNonNull(database, "database").pool();
    }

    /** Returns the distinct hashes that are not currently present in storage. */
    public Set<String> missingHashes(List<String> hashes) throws SQLException {
        Objects.requireNonNull(hashes, "hashes");
        Set<String> uniqueHashes = new LinkedHashSet<>();
        for (String hash : hashes) uniqueHashes.add(normalizeHash(hash));
        if (uniqueHashes.isEmpty()) return Set.of();

        List<String> ordered = new ArrayList<>(uniqueHashes);
        Set<String> present = new LinkedHashSet<>();
        Connection c = pool.borrow();
        try {
            for (int start = 0; start < ordered.size(); start += SQL_BATCH_SIZE) {
                int end = Math.min(start + SQL_BATCH_SIZE, ordered.size());
                String placeholders = placeholders(end - start);
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT hash FROM chunks WHERE hash IN (" + placeholders + ")")) {
                    for (int i = start; i < end; i++) ps.setString(i - start + 1, ordered.get(i));
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) present.add(normalizeHash(rs.getString("hash")));
                    }
                }
            }
        } finally {
            pool.release(c);
        }

        Set<String> missing = new LinkedHashSet<>(uniqueHashes);
        missing.removeAll(present);
        return Set.copyOf(missing);
    }

    /**
     * Verifies and stores one chunk. INSERT IGNORE makes concurrent uploads of
     * the same hash safe; the stored row is then checked before success returns.
     */
    public void saveChunk(String hash, int size, byte[] content) throws SQLException {
        String normalizedHash = normalizeHash(hash);
        Objects.requireNonNull(content, "content");
        if (size <= 0 || size > RabinChunker.MAX_SIZE) {
            throw new IllegalArgumentException("Chunk size is outside the supported range");
        }
        if (size != content.length) throw new IllegalArgumentException("Declared chunk size does not match payload");
        if (!normalizedHash.equals(sha256(content))) {
            throw new IllegalArgumentException("Chunk payload does not match its SHA-256 hash");
        }

        Connection c = pool.borrow();
        try {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT IGNORE INTO chunks(hash,size,content) VALUES(?,?,?)")) {
                ps.setString(1, normalizedHash);
                ps.setInt(2, size);
                ps.setBytes(3, content);
                ps.executeUpdate();
            }

            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT size,content FROM chunks WHERE hash=? FOR UPDATE")) {
                ps.setString(1, normalizedHash);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) throw new SQLException("Chunk was not present after insert: " + normalizedHash);
                    int storedSize = rs.getInt("size");
                    byte[] storedContent = rs.getBytes("content");
                    if (storedContent == null || storedSize != size || storedContent.length != size) {
                        throw new SQLException("Stored chunk size is inconsistent: " + normalizedHash);
                    }
                    if (!normalizedHash.equals(sha256(storedContent)) || !Arrays.equals(content, storedContent)) {
                        throw new SQLException("Stored chunk content does not match its SHA-256 key: " + normalizedHash);
                    }
                }
            }
            c.commit();
        } catch (SQLException | RuntimeException e) {
            try { c.rollback(); } catch (SQLException ignored) { }
            throw e;
        } finally {
            try { c.setAutoCommit(true); } catch (SQLException ignored) { }
            pool.release(c);
        }
    }

    /**
     * Ensures a version's mapping matches its persisted manifest. Chunked file
     * creation should use Database.applyFileChangeChunked so the file row,
     * version row, and mapping are committed atomically together.
     */
    public void saveVersionChunks(long fileId, long version, ChunkManifest manifest) throws SQLException {
        requireVersionKey(fileId, version);
        Objects.requireNonNull(manifest, "manifest");

        Connection c = pool.borrow();
        try {
            c.setAutoCommit(false);
            String storedManifestText;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT manifest FROM file_versions WHERE file_id=? AND version=? FOR UPDATE")) {
                ps.setLong(1, fileId);
                ps.setLong(2, version);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) throw new SQLException("File version does not exist: " + fileId + " v" + version);
                    storedManifestText = rs.getString("manifest");
                }
            }

            ChunkManifest storedManifest;
            try {
                storedManifest = ChunkManifest.parse(storedManifestText);
            } catch (IllegalArgumentException e) {
                throw new SQLException("Persisted version manifest is invalid", e);
            }
            if (!storedManifest.serialize().equals(manifest.serialize())) {
                throw new SQLException("Provided manifest does not match the persisted file version");
            }

            verifyChunksPresent(c, manifest);
            insertVersionChunks(c, fileId, version, manifest);
            verifyVersionMappings(c, fileId, version, manifest);
            c.commit();
        } catch (SQLException | RuntimeException e) {
            try { c.rollback(); } catch (SQLException ignored) { }
            throw e;
        } finally {
            try { c.setAutoCommit(true); } catch (SQLException ignored) { }
            pool.release(c);
        }
    }

    /** Loads and verifies the bytes for one hash-addressed chunk. */
    public byte[] loadChunk(String hash) throws SQLException {
        String normalizedHash = normalizeHash(hash);
        Connection c = pool.borrow();
        try (PreparedStatement ps = c.prepareStatement("SELECT size,content FROM chunks WHERE hash=?")) {
            ps.setString(1, normalizedHash);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new SQLException("Chunk not found: " + normalizedHash);
                int size = rs.getInt("size");
                byte[] content = rs.getBytes("content");
                if (content == null || size <= 0 || size != content.length || size > RabinChunker.MAX_SIZE) {
                    throw new SQLException("Stored chunk has an invalid size: " + normalizedHash);
                }
                if (!normalizedHash.equals(sha256(content))) {
                    throw new SQLException("Stored chunk failed SHA-256 verification: " + normalizedHash);
                }
                return content;
            }
        } finally {
            pool.release(c);
        }
    }

    /** Loads and validates a snapshot manifest and its ordered DB mapping. */
    public ChunkManifest loadManifest(long fileId, long version, long totalSize) throws SQLException {
        requireVersionKey(fileId, version);
        if (totalSize < 0) throw new IllegalArgumentException("Total size cannot be negative");

        Connection c = pool.borrow();
        try {
            c.setAutoCommit(false);
            String manifestText;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT manifest FROM file_versions WHERE file_id=? AND version=?")) {
                ps.setLong(1, fileId);
                ps.setLong(2, version);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) throw new SQLException("File version does not exist: " + fileId + " v" + version);
                    manifestText = rs.getString("manifest");
                }
            }

            ChunkManifest manifest;
            try {
                manifest = ChunkManifest.parse(manifestText);
            } catch (IllegalArgumentException e) {
                throw new SQLException("Persisted version manifest is invalid", e);
            }
            if (manifest.totalSize() != totalSize) {
                throw new SQLException("Manifest total size does not match the requested size");
            }
            verifyChunksPresent(c, manifest);
            verifyVersionMappings(c, fileId, version, manifest);
            c.commit();
            return manifest;
        } catch (SQLException | RuntimeException e) {
            try { c.rollback(); } catch (SQLException ignored) { }
            throw e;
        } finally {
            try { c.setAutoCommit(true); } catch (SQLException ignored) { }
            pool.release(c);
        }
    }

    /**
     * Deletes chunks with no version mapping. The foreign key from mappings to
     * chunks prevents removal of any chunk that a committed version references.
     */
    public int purgeOrphanChunks() throws SQLException {
        Connection c = pool.borrow();
        try {
            c.setAutoCommit(false);
            int deleted;
            try (PreparedStatement ps = c.prepareStatement("""
                    DELETE chunk_row FROM chunks chunk_row
                    WHERE NOT EXISTS (
                      SELECT 1 FROM file_version_chunks mapping
                      WHERE mapping.chunk_hash=chunk_row.hash
                    )
                    """)) {
                deleted = ps.executeUpdate();
            }
            c.commit();
            return deleted;
        } catch (SQLException | RuntimeException e) {
            try { c.rollback(); } catch (SQLException ignored) { }
            throw e;
        } finally {
            try { c.setAutoCommit(true); } catch (SQLException ignored) { }
            pool.release(c);
        }
    }

    private void verifyChunksPresent(Connection c, ChunkManifest manifest) throws SQLException {
        Map<String, Integer> expectedSizes = new LinkedHashMap<>();
        for (ChunkManifest.Entry entry : manifest.chunks()) {
            Integer previous = expectedSizes.putIfAbsent(entry.hash(), entry.length());
            if (previous != null && previous != entry.length()) {
                throw new SQLException("Manifest assigns multiple sizes to chunk " + entry.hash());
            }
        }

        List<Map.Entry<String, Integer>> uniqueChunks = new ArrayList<>(expectedSizes.entrySet());
        for (int start = 0; start < uniqueChunks.size(); start += SQL_BATCH_SIZE) {
            int end = Math.min(start + SQL_BATCH_SIZE, uniqueChunks.size());
            String placeholders = placeholders(end - start);
            Map<String, Integer> foundSizes = new HashMap<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT hash,size FROM chunks WHERE hash IN (" + placeholders + ")")) {
                for (int i = start; i < end; i++) ps.setString(i - start + 1, uniqueChunks.get(i).getKey());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) foundSizes.put(normalizeHash(rs.getString("hash")), rs.getInt("size"));
                }
            }
            for (int i = start; i < end; i++) {
                Map.Entry<String, Integer> expected = uniqueChunks.get(i);
                Integer actual = foundSizes.get(expected.getKey());
                if (actual == null) throw new SQLException("Required chunk is missing: " + expected.getKey());
                if (!actual.equals(expected.getValue())) {
                    throw new SQLException("Stored chunk size mismatch: " + expected.getKey());
                }
            }
        }
    }

    private void insertVersionChunks(Connection c, long fileId, long version,
                                     ChunkManifest manifest) throws SQLException {
        if (manifest.chunkCount() == 0) return;
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT IGNORE INTO file_version_chunks(file_id,version,chunk_order,chunk_hash,chunk_size)
                VALUES(?,?,?,?,?)
                """)) {
            int order = 0;
            int pending = 0;
            for (ChunkManifest.Entry chunk : manifest.chunks()) {
                ps.setLong(1, fileId);
                ps.setLong(2, version);
                ps.setInt(3, order++);
                ps.setString(4, chunk.hash());
                ps.setInt(5, chunk.length());
                ps.addBatch();
                if (++pending == SQL_BATCH_SIZE) {
                    ps.executeBatch();
                    pending = 0;
                }
            }
            if (pending > 0) ps.executeBatch();
        }
    }

    private void verifyVersionMappings(Connection c, long fileId, long version,
                                        ChunkManifest manifest) throws SQLException {
        int order = 0;
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT chunk_order,chunk_hash,chunk_size
                FROM file_version_chunks WHERE file_id=? AND version=? ORDER BY chunk_order ASC
                """)) {
            ps.setLong(1, fileId);
            ps.setLong(2, version);
            try (ResultSet rs = ps.executeQuery()) {
                for (ChunkManifest.Entry expected : manifest.chunks()) {
                    if (!rs.next()) throw new SQLException("File version has an incomplete chunk mapping");
                    if (rs.getInt("chunk_order") != order
                            || !expected.hash().equals(normalizeHash(rs.getString("chunk_hash")))
                            || expected.length() != rs.getInt("chunk_size")) {
                        throw new SQLException("File version mapping does not match its manifest");
                    }
                    order++;
                }
                if (rs.next()) throw new SQLException("File version has unexpected chunk mappings");
            }
        }
    }

    private String normalizeHash(String hash) {
        if (hash == null || !SHA256.matcher(hash).matches()) {
            throw new IllegalArgumentException("Chunk hash must be 64 hexadecimal SHA-256 characters");
        }
        return hash.toLowerCase(Locale.ROOT);
    }

    private String sha256(byte[] content) {
        try {
            return HEX.formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Java runtime does not provide SHA-256", e);
        }
    }

    private String placeholders(int count) {
        return String.join(",", java.util.Collections.nCopies(count, "?"));
    }

    private void requireVersionKey(long fileId, long version) {
        if (fileId <= 0 || version <= 0) throw new IllegalArgumentException("Invalid file or version number");
    }
}
