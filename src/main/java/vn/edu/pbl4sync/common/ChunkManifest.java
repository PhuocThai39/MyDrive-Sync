package vn.edu.pbl4sync.common;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/** Ordered description of a file's chunks. */
public final class ChunkManifest {
    private static final String HEADER = "PBL4SYNC_MANIFEST_V1";
    private static final Pattern DECIMAL = Pattern.compile("(?:0|[1-9][0-9]*)");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-fA-F]{64}");
    private static final HexFormat HEX = HexFormat.of();

    /** Metadata for one chunk in the manifest. */
    public record Entry(String hash, int length) {
        public Entry {
            if (hash == null || !SHA256.matcher(hash).matches()) {
                throw new IllegalArgumentException("Chunk hash must be 64 hexadecimal SHA-256 characters");
            }
            if (length <= 0) throw new IllegalArgumentException("Chunk length must be positive");
            hash = hash.toLowerCase(Locale.ROOT);
        }
    }

    private final long totalSize;
    private final List<Entry> chunks;

    private ChunkManifest(long totalSize, List<Entry> chunks) {
        if (totalSize < 0) throw new IllegalArgumentException("Total size cannot be negative");
        Objects.requireNonNull(chunks, "chunks");

        long summedLength = 0;
        for (Entry chunk : chunks) {
            Objects.requireNonNull(chunk, "chunk");
            try {
                summedLength = Math.addExact(summedLength, chunk.length());
            } catch (ArithmeticException e) {
                throw new IllegalArgumentException("Chunk lengths overflow totalSize", e);
            }
        }
        if (summedLength != totalSize) {
            throw new IllegalArgumentException("Chunk lengths do not add up to totalSize");
        }
        if (totalSize == 0 && !chunks.isEmpty()) {
            throw new IllegalArgumentException("An empty file cannot contain chunks");
        }

        this.totalSize = totalSize;
        this.chunks = List.copyOf(chunks);
    }

    /** Creates a manifest from the ordered chunks returned by {@link RabinChunker}. */
    public static ChunkManifest from(List<RabinChunker.Chunk> chunks, long totalSize) {
        Objects.requireNonNull(chunks, "chunks");
        List<Entry> entries = new ArrayList<>(chunks.size());
        long expectedOffset = 0;
        for (RabinChunker.Chunk chunk : chunks) {
            Objects.requireNonNull(chunk, "chunk");
            if (chunk.offset() != expectedOffset) {
                throw new IllegalArgumentException("Chunk offsets must be contiguous and ordered");
            }
            entries.add(new Entry(chunk.hash(), chunk.length()));
            try {
                expectedOffset = Math.addExact(expectedOffset, chunk.length());
            } catch (ArithmeticException e) {
                throw new IllegalArgumentException("Chunk lengths overflow totalSize", e);
            }
        }
        if (expectedOffset != totalSize) {
            throw new IllegalArgumentException("Chunk lengths do not add up to totalSize");
        }
        return new ChunkManifest(totalSize, entries);
    }

    /** Parses and validates the complete PBL4SYNC_MANIFEST_V1 text format. */
    public static ChunkManifest parse(String text) {
        if (text == null) throw new IllegalArgumentException("Manifest text cannot be null");
        String[] lines = text.split("\\R", -1);
        int lineCount = lines.length;
        if (lineCount > 0 && lines[lineCount - 1].isEmpty()) lineCount--;
        if (lineCount < 3 || !HEADER.equals(lines[0])) {
            throw new IllegalArgumentException("Invalid manifest header or missing metadata");
        }

        long totalSize = parseLongField(lines[1], "totalSize=");
        int chunkCount = parseIntField(lines[2], "chunkCount=");
        if (chunkCount < 0) throw new IllegalArgumentException("chunkCount cannot be negative");
        if (lineCount != 3L + chunkCount) {
            throw new IllegalArgumentException("chunkCount does not match manifest entries");
        }

        List<Entry> chunks = new ArrayList<>(chunkCount);
        for (int i = 0; i < chunkCount; i++) {
            String[] fields = lines[i + 3].split(" ", -1);
            if (fields.length != 2) {
                throw new IllegalArgumentException("Invalid chunk entry on manifest line " + (i + 4));
            }
            int length = parseDecimalInt(fields[1], "chunk length");
            chunks.add(new Entry(fields[0], length));
        }
        return new ChunkManifest(totalSize, chunks);
    }

    /** Serializes this manifest using UTF-8-compatible plain text and LF line endings. */
    public String serialize() {
        StringBuilder text = new StringBuilder();
        text.append(HEADER).append('\n')
                .append("totalSize=").append(totalSize).append('\n')
                .append("chunkCount=").append(chunks.size()).append('\n');
        for (Entry chunk : chunks) {
            text.append(chunk.hash()).append(' ').append(chunk.length()).append('\n');
        }
        return text.toString();
    }

    /** Writes the manifest, creating its parent directory when necessary. */
    public void writeTo(Path path) throws IOException {
        Objects.requireNonNull(path, "path");
        Path absolute = path.toAbsolutePath().normalize();
        Path parent = absolute.getParent();
        if (parent != null) Files.createDirectories(parent);
        Files.writeString(absolute, serialize(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
    }

    /** Reads UTF-8 manifest text and validates it with {@link #parse(String)}. */
    public static ChunkManifest readFrom(Path path) throws IOException {
        Objects.requireNonNull(path, "path");
        return parse(Files.readString(path, StandardCharsets.UTF_8));
    }

    public List<String> allHashes() {
        return chunks.stream().map(Entry::hash).toList();
    }

    public long totalSize() {
        return totalSize;
    }

    public int chunkCount() {
        return chunks.size();
    }

    public List<Entry> chunks() {
        return chunks;
    }

    /**
     * Reassembles and verifies all chunks before replacing the destination.
     * A missing, wrong-sized, or hash-mismatched chunk leaves the destination
     * unchanged.
     */
    public void assembleTo(Path destination, ChunkReader reader) throws IOException {
        Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(reader, "reader");
        Path absolute = destination.toAbsolutePath().normalize();
        Path parent = absolute.getParent();
        if (parent == null) throw new IOException("Destination has no parent directory");
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, ".chunk-manifest-", ".tmp");

        boolean moved = false;
        try {
            long assembledSize = 0;
            try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(temporary,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE))) {
                for (Entry chunk : chunks) {
                    byte[] content = reader.read(chunk.hash());
                    if (content == null) throw new IOException("Missing chunk: " + chunk.hash());
                    if (content.length != chunk.length()) {
                        throw new IOException("Chunk size mismatch: " + chunk.hash());
                    }
                    if (!chunk.hash().equals(sha256(content))) {
                        throw new IOException("Chunk SHA-256 mismatch: " + chunk.hash());
                    }
                    out.write(content);
                    assembledSize = Math.addExact(assembledSize, content.length);
                }
            }
            if (assembledSize != totalSize) throw new IOException("Assembled size does not match manifest");

            try {
                Files.move(temporary, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING);
            }
            moved = true;
        } catch (ArithmeticException e) {
            throw new IOException("Assembled file size overflow", e);
        } finally {
            if (!moved) Files.deleteIfExists(temporary);
        }
    }

    private static long parseLongField(String line, String prefix) {
        if (!line.startsWith(prefix)) throw new IllegalArgumentException("Expected " + prefix + " field");
        String value = line.substring(prefix.length());
        if (!DECIMAL.matcher(value).matches()) throw new IllegalArgumentException("Invalid " + prefix + " value");
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid " + prefix + " value", e);
        }
    }

    private static int parseIntField(String line, String prefix) {
        if (!line.startsWith(prefix)) throw new IllegalArgumentException("Expected " + prefix + " field");
        return parseDecimalInt(line.substring(prefix.length()), prefix + " value");
    }

    private static int parseDecimalInt(String value, String label) {
        if (!DECIMAL.matcher(value).matches()) throw new IllegalArgumentException("Invalid " + label);
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid " + label, e);
        }
    }

    private static String sha256(byte[] content) {
        try {
            return HEX.formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Java runtime does not provide SHA-256", e);
        }
    }

    @FunctionalInterface
    public interface ChunkReader {
        byte[] read(String hash);
    }
}
