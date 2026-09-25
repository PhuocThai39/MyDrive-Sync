package vn.edu.pbl4sync.client.sync;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.BiConsumer;

import static java.nio.file.StandardWatchEventKinds.*;

public class FolderWatcher implements AutoCloseable {
    private final Path root;
    private final IgnoreRegistry ignore;
    private final BiConsumer<String, String> callback;
    private final WatchService watchService;
    private final Map<WatchKey, Path> keys = new ConcurrentHashMap<>();
    private final ScheduledExecutorService debounce = Executors.newSingleThreadScheduledExecutor();
    private final Map<Path, Pending> pending = new ConcurrentHashMap<>();
    private volatile boolean running = true;

    private record Pending(String action, ScheduledFuture<?> future) { }

    public FolderWatcher(Path root, IgnoreRegistry ignore, BiConsumer<String, String> callback) throws IOException {
        this.root = root.toAbsolutePath().normalize();
        this.ignore = ignore;
        this.callback = callback;
        Files.createDirectories(this.root);
        this.watchService = FileSystems.getDefault().newWatchService();
        registerAll(this.root);
        Thread t = new Thread(this::loop, "watch-" + root.getFileName());
        t.setDaemon(true);
        t.start();
    }

    private void registerAll(Path start) throws IOException {
        Files.walkFileTree(start, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                WatchKey key = dir.register(watchService, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE);
                keys.put(key, dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void loop() {
        while (running) {
            WatchKey key;
            try { key = watchService.take(); }
            catch (Exception e) { break; }
            Path dir = keys.get(key);
            if (dir == null) { key.reset(); continue; }
            for (WatchEvent<?> e : key.pollEvents()) {
                if (e.kind() == OVERFLOW) continue;
                Path child = dir.resolve((Path)e.context()).toAbsolutePath().normalize();
                if (ignore.shouldIgnore(child) || SyncFileFilter.shouldIgnore(child)) {
                 continue;
                }
                try {
                    if (e.kind() == ENTRY_CREATE && Files.isDirectory(child)) {
                        registerAll(child);
                        continue;
                    }
                    if (Files.isDirectory(child)) continue;
                } catch (Exception ignored) { }
                String rel = root.relativize(child).toString().replace('\\', '/');
                String action = e.kind() == ENTRY_DELETE ? "DELETE" : (e.kind() == ENTRY_CREATE ? "CREATE" : "MODIFY");
                schedule(child, rel, action);
            }
            if (!key.reset()) keys.remove(key);
        }
    }

    private void schedule(Path absolute, String relative, String action) {
    Pending old = pending.get(absolute);

    // Hủy event đang chờ của cùng file
    if (old != null) {
        old.future().cancel(false);
    }

    // DELETE: chưa gửi ngay, chờ xem có CREATE lại không
    if ("DELETE".equals(action)) {
        ScheduledFuture<?> future = debounce.schedule(() -> {
            pending.remove(absolute);

            // Sau thời gian chờ mà file vẫn không tồn tại
            // => đây mới là DELETE thật
            if (!ignore.shouldIgnore(absolute)
                    && !SyncFileFilter.shouldIgnore(absolute)
                    && !Files.exists(absolute)) {

                callback.accept("DELETE", relative);
            }
        }, 900, TimeUnit.MILLISECONDS);

        pending.put(absolute, new Pending("DELETE", future));
        return;
    }

    String effective = action;

    // DELETE -> CREATE nhanh
    // => Word/Office đang thay thế file cũ
    // => coi toàn bộ là MODIFY
    if (old != null && "DELETE".equals(old.action())
            && "CREATE".equals(action)) {
        effective = "MODIFY";
    }

    // CREATE -> MODIFY
    // => file vừa tạo xong rồi tiếp tục được ghi
    if (old != null && "CREATE".equals(old.action())
            && "MODIFY".equals(action)) {
        effective = "CREATE";
    }

    String finalAction = effective;

    ScheduledFuture<?> future = debounce.schedule(() -> {
        pending.remove(absolute);

        if (!ignore.shouldIgnore(absolute)
                && !SyncFileFilter.shouldIgnore(absolute)
                && Files.exists(absolute)
                && Files.isRegularFile(absolute)) {

            callback.accept(finalAction, relative);
        }
    }, 550, TimeUnit.MILLISECONDS);

    pending.put(absolute, new Pending(effective, future));
}

    @Override public void close() {
        running = false;
        debounce.shutdownNow();
        try { watchService.close(); } catch (IOException ignored) { }
    }
}
