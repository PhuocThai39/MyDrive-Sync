package vn.edu.pbl4sync.client.sync;

import java.nio.file.Path;

public final class SyncFileFilter {

    private SyncFileFilter() {}

    public static boolean shouldIgnore(Path path) {
    if (path == null || path.getFileName() == null) return true;

    String name = path.getFileName().toString();

    return name.startsWith(".pbl4sync")
            || name.startsWith("~$")
            || name.startsWith("~WRD")
            || name.startsWith("~WRL")
            || name.startsWith("~WRC")
            || name.endsWith(".tmp")
            || name.endsWith(".bak")
            || name.equalsIgnoreCase("Thumbs.db")
            || name.equalsIgnoreCase("desktop.ini")
            || name.endsWith(".crdownload");
}
}