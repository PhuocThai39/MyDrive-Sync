package vn.edu.pbl4sync.common;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * Splits files into content-defined chunks using a rolling Rabin fingerprint.
 * Each chunk is identified by the SHA-256 digest of its bytes.
 */
public final class RabinChunker {
    public static final long POLY = 0x3DA3358B4DC173L;
    public static final int WINDOW = 64;
    public static final int MIN_SIZE = 1 * 1024 * 1024;
    public static final int MAX_SIZE = 16 * 1024 * 1024;
    public static final long MASK_TARGET = (1L << 22) - 1;

    private static final int POLY_DEGREE = 63 - Long.numberOfLeadingZeros(POLY);
    private static final int BUFFER_SIZE = 64 * 1024;
    private static final long[] MOD_TABLE = buildModTable();
    private static final long[] OUT_TABLE = buildOutTable();
    private static final HexFormat HEX = HexFormat.of();

    /** A chunk's location in the source file and its content hash. */
    public record Chunk(long offset, int length, String hash) {
        public Chunk {
            if (offset < 0) throw new IllegalArgumentException("Chunk offset cannot be negative");
            if (length <= 0) throw new IllegalArgumentException("Chunk length must be positive");
            if (hash == null || hash.length() != 64) {
                throw new IllegalArgumentException("Chunk hash must be a 64-character SHA-256 hex string");
            }
        }
    }

    /** Utility class; use {@link #chunk(Path)}. */
    private RabinChunker() {}

    /**
     * Reads a regular file and returns its content-defined chunks in file order.
     * Empty files produce an empty list. A non-empty file always produces at
     * least one chunk, with each chunk between MIN_SIZE and MAX_SIZE except
     * that the final chunk may be smaller than MIN_SIZE.
     *
     * @throws IOException if the file cannot be read or its size/metadata changes
     *                     while it is being chunked
     */
    public static List<Chunk> chunk(Path file) throws IOException {
        Objects.requireNonNull(file, "file");
        BasicFileAttributes before = Files.readAttributes(file, BasicFileAttributes.class);
        if (!before.isRegularFile()) throw new IOException("Not a regular file: " + file);

        List<Chunk> chunks = new ArrayList<>();
        byte[] buffer = new byte[BUFFER_SIZE];
        byte[] window = new byte[WINDOW];
        int windowPosition = 0;
        int windowCount = 0;
        long fingerprint = 0;
        long chunkOffset = 0;
        int chunkLength = 0;
        long bytesRead = 0;
        MessageDigest digest = newSha256();

        try (InputStream in = new BufferedInputStream(Files.newInputStream(file))) {
            int count;
            while ((count = in.read(buffer)) != -1) {
                int digestSegmentStart = 0;
                bytesRead += count;

                for (int i = 0; i < count; i++) {
                    int nextByte = buffer[i] & 0xff;

                    if (windowCount == WINDOW) {
                        int outgoingByte = window[windowPosition] & 0xff;
                        fingerprint ^= OUT_TABLE[outgoingByte];
                    } else {
                        windowCount++;
                    }

                    window[windowPosition] = buffer[i];
                    windowPosition = (windowPosition + 1) % WINDOW;
                    fingerprint = appendByte(fingerprint, nextByte);
                    chunkLength++;

                    boolean maxReached = chunkLength >= MAX_SIZE;
                    boolean naturalBoundary = chunkLength >= MIN_SIZE
                            && (fingerprint & MASK_TARGET) == 0;
                    if (maxReached || naturalBoundary) {
                        digest.update(buffer, digestSegmentStart, i - digestSegmentStart + 1);
                        chunks.add(new Chunk(chunkOffset, chunkLength, HEX.formatHex(digest.digest())));
                        chunkOffset += chunkLength;
                        chunkLength = 0;
                        digest = newSha256();

                        // Each chunk starts a fresh rolling window and fingerprint.
                        fingerprint = 0;
                        windowPosition = 0;
                        windowCount = 0;
                        digestSegmentStart = i + 1;
                    }
                }

                if (digestSegmentStart < count) {
                    digest.update(buffer, digestSegmentStart, count - digestSegmentStart);
                }
            }
        }

        if (chunkLength > 0) {
            chunks.add(new Chunk(chunkOffset, chunkLength, HEX.formatHex(digest.digest())));
        }

        BasicFileAttributes after = Files.readAttributes(file, BasicFileAttributes.class);
        if (bytesRead != before.size()
                || before.size() != after.size()
                || !before.lastModifiedTime().equals(after.lastModifiedTime())
                || (before.fileKey() != null && !Objects.equals(before.fileKey(), after.fileKey()))) {
            throw new IOException("File changed while being chunked: " + file);
        }

        return List.copyOf(chunks);
    }

    /**
     * Reads exactly {@code length} bytes at {@code offset} from a file.
     *
     * @throws IllegalArgumentException if offset or length is negative, or their
     *                                  sum overflows a long
     * @throws IOException if the requested range extends beyond the file
     */
    public static byte[] readChunk(Path file, long offset, int length) throws IOException {
        Objects.requireNonNull(file, "file");
        if (offset < 0) throw new IllegalArgumentException("Chunk offset cannot be negative");
        if (length < 0) throw new IllegalArgumentException("Chunk length cannot be negative");
        if (offset > Long.MAX_VALUE - length) throw new IllegalArgumentException("Chunk range overflows");

        byte[] result = new byte[length];
        if (length == 0) {
            if (offset > Files.size(file)) throw new IOException("Chunk offset is beyond end of file");
            return result;
        }

        try (var channel = java.nio.channels.FileChannel.open(file, java.nio.file.StandardOpenOption.READ)) {
            java.nio.ByteBuffer destination = java.nio.ByteBuffer.wrap(result);
            long position = offset;
            while (destination.hasRemaining()) {
                int count = channel.read(destination, position);
                if (count < 0) throw new IOException("Unexpected end of file while reading chunk");
                if (count == 0) continue;
                position += count;
            }
        }
        return result;
    }

    private static long appendByte(long fingerprint, int value) {
        long extended = (fingerprint << Byte.SIZE) | (value & 0xffL);
        int high = (int) (extended >>> POLY_DEGREE);
        return extended ^ ((long) high << POLY_DEGREE) ^ MOD_TABLE[high];
    }

    private static long[] buildModTable() {
        long[] table = new long[256];
        for (int i = 0; i < table.length; i++) {
            table[i] = polynomialRemainder((long) i << POLY_DEGREE);
        }
        return table;
    }

    private static long[] buildOutTable() {
        long[] table = new long[256];
        for (int value = 0; value < table.length; value++) {
            long fingerprint = value;
            for (int i = 0; i < WINDOW - 1; i++) {
                fingerprint = appendByte(fingerprint, 0);
            }
            table[value] = fingerprint;
        }
        return table;
    }

    /** Returns the GF(2) remainder; all callers supply values no wider than 61 bits. */
    private static long polynomialRemainder(long value) {
        for (int bit = 63; bit >= POLY_DEGREE; bit--) {
            if ((value & (1L << bit)) != 0) {
                value ^= POLY << (bit - POLY_DEGREE);
            }
        }
        return value;
    }

    private static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Java runtime does not provide SHA-256", e);
        }
    }
}
