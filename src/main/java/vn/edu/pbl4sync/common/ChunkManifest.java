package vn.edu.pbl4sync.common;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Snapshot Manifest — "bản đồ" của một version file.
 *
 * <p>Thay vì lưu toàn bộ nội dung file, mỗi version chỉ cần lưu:
 * <ul>
 *   <li>Danh sách SHA-256 hash của các chunk theo đúng thứ tự
 *   <li>Kích thước từng chunk (để tái tạo lại file chính xác)
 *   <li>Tổng kích thước file gốc (để kiểm tra sau khi assemble)
 * </ul>
 *
 * <p>Khi rollback về version cũ, chỉ cần đọc manifest của version đó
 * rồi ghép chunk từ chunk store — không cần đi qua version trung gian nào.
 *
 * <p>Format lưu trữ (text, UTF-8):
 * <pre>
 * PBL4SYNC_MANIFEST_V1
 * totalSize=&lt;long&gt;
 * chunkCount=&lt;int&gt;
 * &lt;hash&gt; &lt;length&gt;
 * &lt;hash&gt; &lt;length&gt;
 * ...
 * </pre>
 */
public final class ChunkManifest {

    private static final String HEADER = "PBL4SYNC_MANIFEST_V1";

    /** Một entry trong manifest: hash và kích thước của chunk. */
    public record Entry(String hash, int length) {}

    private final long totalSize;
    private final List<Entry> entries;

    public ChunkManifest(long totalSize, List<Entry> entries) {
        this.totalSize = totalSize;
        this.entries = Collections.unmodifiableList(new ArrayList<>(entries));
    }

    // ---------------------------------------------------------------------------
    // Factory methods
    // ---------------------------------------------------------------------------

    /**
     * Tạo ChunkManifest từ kết quả chunking của {@link RabinChunker}.
     *
     * @param chunks danh sách chunk trả về từ {@code RabinChunker.chunk(file)}
     * @param totalSize kích thước file gốc (bytes)
     */
    public static ChunkManifest from(List<RabinChunker.Chunk> chunks, long totalSize) {
        List<Entry> entries = new ArrayList<>(chunks.size());
        for (RabinChunker.Chunk c : chunks) {
            entries.add(new Entry(c.hash(), c.length()));
        }
        return new ChunkManifest(totalSize, entries);
    }

    /**
     * Parse manifest từ chuỗi text (đọc từ DB hoặc từ file).
     *
     * @param text nội dung manifest dạng text
     * @throws IllegalArgumentException nếu format sai
     */
    public static ChunkManifest parse(String text) {
        if (text == null || text.isBlank())
            throw new IllegalArgumentException("Manifest text is empty");

        String[] lines = text.split("\n");
        if (lines.length < 3)
            throw new IllegalArgumentException("Manifest too short");

        if (!HEADER.equals(lines[0].trim()))
            throw new IllegalArgumentException("Unknown manifest format: " + lines[0].trim());

        long totalSize = 0;
        int chunkCount = 0;

        // Parse header fields
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.startsWith("totalSize=")) {
                totalSize = Long.parseLong(line.substring("totalSize=".length()));
            } else if (line.startsWith("chunkCount=")) {
                chunkCount = Integer.parseInt(line.substring("chunkCount=".length()));
                // Dòng tiếp theo là các entry chunk
                List<Entry> entries = new ArrayList<>(chunkCount);
                for (int j = i + 1; j < lines.length && entries.size() < chunkCount; j++) {
                    String[] parts = lines[j].trim().split(" ");
                    if (parts.length != 2)
                        throw new IllegalArgumentException("Invalid chunk entry at line " + (j + 1));
                    entries.add(new Entry(parts[0], Integer.parseInt(parts[1])));
                }
                if (entries.size() != chunkCount)
                    throw new IllegalArgumentException(
                            "Expected " + chunkCount + " chunks, got " + entries.size());
                return new ChunkManifest(totalSize, entries);
            }
        }

        throw new IllegalArgumentException("Manifest missing chunkCount field");
    }

    /**
     * Serialize manifest thành chuỗi text để lưu vào DB.
     */
    public String serialize() {
        StringBuilder sb = new StringBuilder();
        sb.append(HEADER).append('\n');
        sb.append("totalSize=").append(totalSize).append('\n');
        sb.append("chunkCount=").append(entries.size()).append('\n');
        for (Entry e : entries) {
            sb.append(e.hash()).append(' ').append(e.length()).append('\n');
        }
        return sb.toString();
    }

    /**
     * Lưu manifest ra file (dùng cho LocalBackupStore).
     */
    public void writeTo(Path path) throws IOException {
        Files.writeString(path, serialize(), StandardCharsets.UTF_8);
    }

    /**
     * Đọc manifest từ file.
     */
    public static ChunkManifest readFrom(Path path) throws IOException {
        return parse(Files.readString(path, StandardCharsets.UTF_8));
    }

    // ---------------------------------------------------------------------------
    // Delta Upload helpers
    // ---------------------------------------------------------------------------

    /**
     * So sánh với manifest cũ (version trước), trả về danh sách hash
     * của các chunk CHƯA có trong manifest cũ — tức là chunk cần upload mới.
     *
     * <p>Đây là trái tim của Delta Upload: client gọi method này để biết
     * chính xác chunk nào cần gửi lên server, bỏ qua chunk đã tồn tại.
     *
     * @param previous manifest của version trước (null = upload toàn bộ)
     * @return danh sách hash chunk cần upload (không trùng lặp, giữ thứ tự xuất hiện đầu tiên)
     */
    public List<String> newChunksComparedTo(ChunkManifest previous) {
        if (previous == null) {
            // Upload toàn bộ — trả về tất cả hash duy nhất theo thứ tự
            List<String> all = new ArrayList<>();
            java.util.Set<String> seen = new java.util.LinkedHashSet<>();
            for (Entry e : entries) seen.add(e.hash());
            all.addAll(seen);
            return all;
        }

        // Hash đã có ở version trước → không cần upload lại
        java.util.Set<String> existing = new java.util.HashSet<>();
        for (Entry e : previous.entries) existing.add(e.hash());

        // Tìm hash xuất hiện trong version mới nhưng chưa có ở version cũ
        List<String> needed = new ArrayList<>();
        java.util.Set<String> added = new java.util.HashSet<>();
        for (Entry e : entries) {
            if (!existing.contains(e.hash()) && added.add(e.hash())) {
                needed.add(e.hash());
            }
        }
        return needed;
    }

    /**
     * Trả về danh sách hash duy nhất của tất cả chunk trong manifest này.
     * Server dùng để kiểm tra chunk nào đã có trong chunk store.
     */
    public List<String> allHashes() {
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        for (Entry e : entries) seen.add(e.hash());
        return new ArrayList<>(seen);
    }

    // ---------------------------------------------------------------------------
    // Assembly helpers
    // ---------------------------------------------------------------------------

    /**
     * Ghép các chunk lại thành file hoàn chỉnh (dùng khi download hoặc rollback).
     *
     * <p>Với mỗi hash trong manifest, gọi {@code chunkReader} để lấy nội dung,
     * rồi ghi tuần tự vào {@code destination}.
     *
     * @param destination  file đích cần ghi
     * @param chunkReader  hàm nhận hash → trả về byte[] nội dung chunk
     * @throws IOException nếu không tìm được chunk hoặc ghi thất bại
     */
    public void assembleTo(Path destination,
                           ChunkReader chunkReader) throws IOException {
        Files.createDirectories(destination.getParent());
        try (OutputStream out = new BufferedOutputStream(
                Files.newOutputStream(destination))) {
            for (Entry e : entries) {
                byte[] data = chunkReader.read(e.hash());
                if (data == null)
                    throw new IOException("Chunk not found in store: " + e.hash());
                if (data.length != e.length())
                    throw new IOException("Chunk size mismatch for hash " + e.hash()
                            + ": expected " + e.length() + ", got " + data.length);
                out.write(data);
            }
        }

        // Kiểm tra kích thước sau khi assemble
        long assembled = Files.size(destination);
        if (assembled != totalSize) {
            Files.deleteIfExists(destination);
            throw new IOException("Assembly size mismatch: expected " + totalSize
                    + " bytes, got " + assembled);
        }
    }

    /** Functional interface để đọc nội dung chunk từ bất kỳ nguồn nào. */
    @FunctionalInterface
    public interface ChunkReader {
        /**
         * @param hash SHA-256 hex của chunk
         * @return nội dung chunk, hoặc null nếu không tìm thấy
         */
        byte[] read(String hash) throws IOException;
    }

    // ---------------------------------------------------------------------------
    // Getters
    // ---------------------------------------------------------------------------

    public long totalSize() { return totalSize; }
    public List<Entry> entries() { return entries; }
    public int chunkCount() { return entries.size(); }

    @Override
    public String toString() {
        return "ChunkManifest{totalSize=" + totalSize
                + ", chunks=" + entries.size() + "}";
    }
}