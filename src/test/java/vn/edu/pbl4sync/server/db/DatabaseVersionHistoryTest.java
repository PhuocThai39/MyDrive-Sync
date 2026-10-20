package vn.edu.pbl4sync.server.db;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vn.edu.pbl4sync.common.HashUtil;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

class DatabaseVersionHistoryTest {
    @TempDir
    Path temp;

    private Database database;
    private long userId;
    private long workspaceId;
    private String url;
    private final List<Path> rollbackPayloads = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        url = "jdbc:h2:mem:versions_" + System.nanoTime()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE";
        database = new Database(url, "sa", "", 4, "admin-pass");
        userId = Long.parseLong(database.findUserByUsername("admin").get("id"));
        workspaceId = database.createWorkspace(userId, "Version Tests", "admin");
    }

    @AfterEach
    void tearDown() throws Exception {
        for (Path path : rollbackPayloads) Files.deleteIfExists(path);
        if (database != null) database.close();
    }

    @Test
    void acceptsSequentialUpdatesAndPersistsEachContentVersion() throws Exception {
        Map<String, String> first = update("a1.txt", "ChuaCoGiCa", 0);
        Map<String, String> second = update("a1.txt", "Cli1", 1);

        assertEquals("1", first.get("version"));
        assertEquals("2", second.get("version"));
        List<Map<String, String>> versions = database.listFileVersions(Long.parseLong(first.get("id")));
        assertEquals(List.of("2", "1"), versions.stream().map(v -> v.get("version")).toList());
        assertEquals("1", versions.get(0).get("base_version"));
        assertEquals("UPDATE", versions.get(0).get("operation"));
    }

    @Test
    void staleBaseVersionIsRejectedAndConflictingContentIsPreserved() throws Exception {
        Map<String, String> initial = update("a1.txt", "server", 0);
        Path conflict = write("client-stale");

        Map<String, String> result = database.applyFileChange(workspaceId, "a1.txt",
                Files.size(conflict), HashUtil.sha256(conflict), userId, "Client2",
                0, false, conflict);

        assertEquals("true", result.get("conflict"));
        assertEquals("1", database.findFile(workspaceId, "a1.txt").get("version"));
        assertEquals(1, database.listFileVersions(Long.parseLong(initial.get("id"))).size());
        try (var connection = DriverManager.getConnection(url, "sa", "");
             var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT content FROM file_conflicts WHERE file_id="
                     + initial.get("id"))) {
            assertTrue(rows.next());
            assertEquals("client-stale", new String(rows.getBytes(1)));
        }
    }

    @Test
    void concurrentUpdatesFromTheSameBaseAllowOnlyOneCommit() throws Exception {
        Map<String, String> initial = update("a1.txt", "v1", 0);
        Path left = write("left");
        Path right = write("right");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Map<String, String>> leftResult = executor.submit(() -> concurrentUpdate(left, ready, start));
            Future<Map<String, String>> rightResult = executor.submit(() -> concurrentUpdate(right, ready, start));
            ready.await();
            start.countDown();
            List<Map<String, String>> results = List.of(leftResult.get(), rightResult.get());
            assertEquals(1, results.stream().filter(r -> "true".equals(r.get("conflict"))).count());
            assertEquals("2", database.findFile(workspaceId, "a1.txt").get("version"));
            assertEquals(2, database.listFileVersions(Long.parseLong(initial.get("id"))).size());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void identicalChecksumDoesNotCreateAnotherVersion() throws Exception {
        Map<String, String> first = update("a1.txt", "same", 0);
        Map<String, String> duplicate = update("a1.txt", "same", 1);

        assertEquals("1", duplicate.get("version"));
        assertEquals("true", duplicate.get("unchanged"));
        assertEquals(1, database.listFileVersions(Long.parseLong(first.get("id"))).size());
    }

    @Test
    void rollbackToOlderContentCreatesNewMonotonicVersionAndChecksExpectedCurrent() throws Exception {
        Map<String, String> current = update("a1.txt", "v1", 0);
        update("a1.txt", "v2", 1);
        update("a1.txt", "v3", 2);
        update("a1.txt", "v4", 3);
        update("a1.txt", "v5", 4);
        long fileId = Long.parseLong(current.get("id"));

        Map<String, String> rollback = database.rollbackFile(workspaceId, "a1.txt", 2, 5, userId, "Admin");
        Path restored = Path.of(rollback.get("content_path"));
        rollbackPayloads.add(restored);
        assertEquals("6", rollback.get("version"));
        assertEquals("5", rollback.get("base_version"));
        assertEquals("2", rollback.get("rollback_from_version"));
        assertEquals("v2", Files.readString(restored));
        assertEquals("ROLLBACK", database.listFileVersions(fileId).get(0).get("operation"));
        Path syncPayload = Files.createTempFile(temp, "sync-", ".txt");
        assertTrue(database.copyFileVersion(fileId, 6, syncPayload));
        assertEquals("v2", Files.readString(syncPayload));

        Map<String, String> staleRollback = database.rollbackFile(workspaceId, "a1.txt", 1, 5, userId, "Admin");
        assertEquals("true", staleRollback.get("conflict"));
        assertEquals("6", database.findFile(workspaceId, "a1.txt").get("version"));
    }

    private Map<String, String> concurrentUpdate(Path payload, CountDownLatch ready, CountDownLatch start)
            throws Exception {
        ready.countDown();
        start.await();
        return database.applyFileChange(workspaceId, "a1.txt", Files.size(payload), HashUtil.sha256(payload),
                userId, "ConcurrentAgent", 1, false, payload);
    }

    private Map<String, String> update(String path, String content, long baseVersion) throws Exception {
        Path payload = write(content);
        return database.applyFileChange(workspaceId, path, Files.size(payload), HashUtil.sha256(payload),
                userId, "TestAgent", baseVersion, false, payload);
    }

    private Path write(String content) throws Exception {
        Path file = Files.createTempFile(temp, "payload-", ".txt");
        Files.writeString(file, content);
        return file;
    }
}
