package vn.edu.pbl4sync.client.sync;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Base64;
import java.util.Properties;

public class LocalStateStore {
    private final Path file;
    private final Properties p = new Properties();

    public LocalStateStore(Path root) {
        this.file = root.resolve(".pbl4sync-state.properties");
        load();
    }

    private synchronized void load() {
        if (!Files.exists(file)) return;
        try (InputStream in = Files.newInputStream(file)) { p.load(in); } catch (Exception ignored) { }
    }

    private String key(long ws, String path) {
        String raw = ws + "|" + path.replace('\\', '/');
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    public synchronized long version(long ws, String path) {
        try { return Long.parseLong(parts(ws, path)[0]); } catch (Exception e) { return 0; }
    }

    public synchronized String checksum(long ws, String path) {
        return parts(ws, path)[1];
    }

    public synchronized void put(long ws, String path, long version, String checksum) {
        p.setProperty(key(ws, path), version + "|" + (checksum == null ? "" : checksum) + "|-1|false");
        save();
    }

    public synchronized long uploadBaseVersion(long ws, String path) {
        try {
            long pendingBase = Long.parseLong(parts(ws, path)[2]);
            return pendingBase >= 0 ? pendingBase : version(ws, path);
        } catch (Exception e) {
            return version(ws, path);
        }
    }

    public synchronized void markRemoteApplied(long ws, String path, long version, String checksum,
                                               long previousVersion) {
        p.setProperty(key(ws, path), version + "|" + (checksum == null ? "" : checksum)
                + "|" + previousVersion + "|false");
        save();
    }

    public synchronized void markConflicted(long ws, String path) {
        String[] current = parts(ws, path);
        p.setProperty(key(ws, path), current[0] + "|" + current[1] + "|" + current[2] + "|true");
        save();
    }

    public synchronized boolean isConflicted(long ws, String path) {
        return Boolean.parseBoolean(parts(ws, path)[3]);
    }

    public synchronized void remove(long ws, String path) {
        p.remove(key(ws, path));
        save();
    }

    private String[] parts(long ws, String path) {
        String[] stored = p.getProperty(key(ws, path), "0||-1|false").split("\\|", -1);
        String[] normalized = {"0", "", "-1", "false"};
        System.arraycopy(stored, 0, normalized, 0, Math.min(stored.length, normalized.length));
        return normalized;
    }

    private void save() {
        try {
            Files.createDirectories(file.getParent());
            try (OutputStream out = Files.newOutputStream(file, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                p.store(out, "PBL4 Sync local file versions");
            }
        } catch (Exception ignored) { }
    }
}
