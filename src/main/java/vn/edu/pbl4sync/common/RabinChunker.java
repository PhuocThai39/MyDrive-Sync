package vn.edu.pbl4sync.common;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Content-Defined Chunking (CDC) dùng thuật toán Rabin fingerprint.
 *
 * <p>Thay vì cắt file theo kích thước cố định, Rabin CDC tìm điểm cắt tự nhiên
 * dựa trên nội dung thực tế của file. Nhờ vậy khi file bị sửa ở giữa, chỉ
 * các chunk bị ảnh hưởng thay đổi — các chunk còn lại vẫn giữ nguyên hash
 * và không cần upload lại (delta upload).
 *
 * <p>Tham số mặc định:
 * <ul>
 *   <li>Chunk mục tiêu : 4 MB  (khi fingerprint & MASK_TARGET == 0)
 *   <li>Chunk tối thiểu: 1 MB  (không cắt sớm hơn)
 *   <li>Chunk tối đa  : 16 MB (buộc cắt nếu chưa tìm được điểm tự nhiên)
 * </ul>
 */
public final class RabinChunker {

    // ---------------------------------------------------------------------------
    // Rabin polynomial & sliding-window constants
    // ---------------------------------------------------------------------------

    /** Đa thức Rabin 64-bit (irreducible) — giống giá trị dùng trong restic/bup. */
    private static final long POLY = 0x3DA3358B4DC173L;

    /** Kích thước cửa sổ trượt (bytes). */
    private static final int WINDOW = 64;

    /**
     * Bảng tra cứu pre-computed cho phép tính cuộn fingerprint trong O(1).
     * out_table[b] = giá trị cần XOR ra khi byte b trượt khỏi cửa sổ.
     */
    private static final long[] OUT_TABLE = new long[256];

    /**
     * Bảng tra cứu cho phép nhân đa thức mod POLY nhanh.
     * mod_table[b] = (b * x^WINDOW) mod POLY  — dùng để cuộn vào byte mới.
     */
    private static final long[] MOD_TABLE = new long[256];

    static {
        // Tính MOD_TABLE: với mỗi byte b, tính b * x^WINDOW mod POLY
        for (int b = 0; b < 256; b++) {
            long h = b;
            for (int i = 0; i < WINDOW; i++) {
                // Nhân x (dịch trái 1 bit); nếu bit cao bị đẩy ra thì XOR với POLY
                if ((h & (1L << 63)) != 0) h = (h << 1) ^ POLY;
                else h <<= 1;
            }
            MOD_TABLE[b] = h;
        }

        // Tính OUT_TABLE: giá trị cần trừ khi byte cũ trượt ra khỏi cửa sổ
        // out_table[b] = MOD_TABLE[b] (cùng công thức, dùng riêng để code rõ ràng)
        System.arraycopy(MOD_TABLE, 0, OUT_TABLE, 0, 256);
    }

    // ---------------------------------------------------------------------------
    // Chunk-size thresholds
    // ---------------------------------------------------------------------------

    /** Chunk tối thiểu: 1 MB — không cắt trước ngưỡng này. */
    public static final int MIN_SIZE = 1 * 1024 * 1024;

    /** Chunk mục tiêu: 4 MB — mask 22 bit để xác suất cắt ≈ 1/4MB. */
    public static final int TARGET_SIZE = 4 * 1024 * 1024;

    /** Chunk tối đa: 16 MB — buộc cắt nếu chưa tìm được điểm tự nhiên. */
    public static final int MAX_SIZE = 16 * 1024 * 1024;

    /**
     * Mask 22 bit: fingerprint & MASK_TARGET == 0 xác suất ≈ 1/4M byte,
     * tức trung bình mỗi chunk ~4 MB.
     */
    private static final long MASK_TARGET = (1L << 22) - 1;

    // ---------------------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------------------

    private RabinChunker() {}

    /**
     * Kết quả của một chunk: vị trí bắt đầu, độ dài, và SHA-256 của nội dung.
     *
     * @param offset  byte offset trong file gốc
     * @param length  số byte trong chunk này
     * @param hash    SHA-256 hex của nội dung chunk (64 ký tự)
     */
    public record Chunk(long offset, int length, String hash) {}

    /**
     * Cắt {@code file} thành các chunk theo CDC Rabin.
     *
     * <p>Trả về danh sách {@link Chunk} theo thứ tự, mỗi phần tử chứa
     * offset, length và SHA-256 hash — đủ để tạo {@code ChunkManifest}
     * mà không cần đọc lại file.
     *
     * @param file đường dẫn file cần cắt
     * @return danh sách chunk (có thể rỗng nếu file rỗng)
     * @throws IOException nếu không đọc được file
     */
    public static List<Chunk> chunk(Path file) throws IOException {
        List<Chunk> result = new ArrayList<>();
        long fileSize = Files.size(file);
        if (fileSize == 0) return result;

        try (InputStream in = Files.newInputStream(file)) {
            // Vòng trượt: buf chứa cửa sổ WINDOW byte gần nhất
            byte[] window = new byte[WINDOW];
            int windowPos = 0;      // vị trí ghi tiếp theo trong window (circular)
            long fingerprint = 0;   // Rabin fingerprint hiện tại

            // Buffer đọc file và buffer tích lũy nội dung chunk hiện tại
            byte[] readBuf = new byte[256 * 1024];  // 256 KB đọc mỗi lần
            byte[] chunkBuf = new byte[MAX_SIZE];
            int chunkLen = 0;
            long offset = 0;

            MessageDigest md = sha256digest();

            int n;
            while ((n = in.read(readBuf)) != -1) {
                for (int i = 0; i < n; i++) {
                    byte incoming = readBuf[i];

                    // 1. Byte cũ sắp bị đẩy ra khỏi cửa sổ trượt
                    byte outgoing = window[windowPos];

                    // 2. Cập nhật cửa sổ trượt (circular buffer)
                    window[windowPos] = incoming;
                    windowPos = (windowPos + 1) % WINDOW;

                    // 3. Cập nhật Rabin fingerprint:
                    //    h = h * x  XOR  MOD_TABLE[outgoing]  XOR  incoming
                    fingerprint = ((fingerprint << 8) | (incoming & 0xFF))
                            ^ OUT_TABLE[outgoing & 0xFF];

                    // 4. Tích lũy byte vào buffer chunk hiện tại
                    chunkBuf[chunkLen++] = incoming;

                    // 5. Kiểm tra điểm cắt
                    boolean naturalCut = chunkLen >= MIN_SIZE
                            && (fingerprint & MASK_TARGET) == 0;
                    boolean forcedCut = chunkLen >= MAX_SIZE;

                    if (naturalCut || forcedCut) {
                        result.add(finalizeChunk(md, chunkBuf, chunkLen, offset));
                        offset += chunkLen;
                        chunkLen = 0;

                        // Reset cửa sổ và fingerprint cho chunk mới
                        Arrays.fill(window, (byte) 0);
                        windowPos = 0;
                        fingerprint = 0;
                    }
                }
            }

            // Chunk cuối (phần còn lại)
            if (chunkLen > 0) {
                result.add(finalizeChunk(md, chunkBuf, chunkLen, offset));
            }
        } catch (Exception e) {
            throw new IOException("CDC chunking failed: " + e.getMessage(), e);
        }

        return result;
    }

    /**
     * Đọc nội dung của một chunk từ file gốc.
     * Dùng khi cần upload nội dung chunk lên server.
     *
     * @param file   file gốc
     * @param offset vị trí bắt đầu (từ {@link Chunk#offset()})
     * @param length số byte (từ {@link Chunk#length()})
     * @return mảng byte nội dung chunk
     */
    public static byte[] readChunk(Path file, long offset, int length) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            long skipped = in.skip(offset);
            if (skipped != offset)
                throw new IOException("Cannot seek to offset " + offset);
            byte[] data = new byte[length];
            int total = 0;
            while (total < length) {
                int r = in.read(data, total, length - total);
                if (r == -1) throw new IOException("Unexpected EOF at offset " + (offset + total));
                total += r;
            }
            return data;
        }
    }

    // ---------------------------------------------------------------------------
    // Private helpers
    // ---------------------------------------------------------------------------

    private static Chunk finalizeChunk(MessageDigest md, byte[] buf,
                                       int len, long offset) {
        md.reset();
        md.update(buf, 0, len);
        String hash = toHex(md.digest());
        return new Chunk(offset, len, hash);
    }

    private static MessageDigest sha256digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}