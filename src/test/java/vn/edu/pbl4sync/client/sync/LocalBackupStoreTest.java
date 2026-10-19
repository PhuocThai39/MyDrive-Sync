package vn.edu.pbl4sync.client.sync;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vn.edu.pbl4sync.common.HashUtil;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LocalBackupStoreTest {
    @TempDir
    Path temp;

    @Test
    void savesChangedContentOnceAndSkipsIdenticalContent() throws Exception {
        Path syncRoot = temp.resolve("CompanySync");
        Path source = temp.resolve("a1.txt");
        Files.writeString(source, "Cli1");
        String oldChecksum = HashUtil.sha256(source);
        Path backup = LocalBackupStore.backupIfDifferent(syncRoot, 7, "Team", "Docs/a1.txt",
                source, 2, oldChecksum, HashUtil.sha256(write("Cli2")));

        assertNotNull(backup);
        assertEquals("Cli1", Files.readString(backup));
        assertEquals(backup, LocalBackupStore.backupIfDifferent(syncRoot, 7, "Team", "Docs/a1.txt",
                source, 3, oldChecksum, HashUtil.sha256(write("Cli3"))));
        assertNull(LocalBackupStore.backupIfDifferent(syncRoot, 7, "Team", "Docs/a1.txt",
                source, 2, oldChecksum, oldChecksum));

        try (var files = Files.list(backup.getParent())) {
            assertEquals(1, files.count());
        }
    }

    @Test
    void keepsEveryDistinctFileVersion() throws Exception {
        Path syncRoot = temp.resolve("CompanySync");
        Path source = temp.resolve("a1.txt");
        Path backup = null;
        for (int version = 1; version <= 7; version++) {
            Files.writeString(source, "content-" + version);
            String current = HashUtil.sha256(source);
            String incoming = HashUtil.sha256(write("incoming-" + version));
            backup = LocalBackupStore.backupIfDifferent(syncRoot, 2, "Team", "a1.txt",
                    source, version, current, incoming);
        }

        assertNotNull(backup);
        try (var files = Files.list(backup.getParent())) {
            assertEquals(7, files.count());
        }
    }

    @Test
    void archivesServerVersionEvenWhenItIsTheIncomingContent() throws Exception {
        Path syncRoot = temp.resolve("CompanySync");
        Path source = write("accepted version");
        String checksum = HashUtil.sha256(source);

        Path backup = LocalBackupStore.archiveVersion(syncRoot, 9, "Team", "Docs/a1.txt",
                source, 4, checksum);

        assertNotNull(backup);
        assertEquals("accepted version", Files.readString(backup));
        assertTrue(backup.startsWith(syncRoot.resolve("BackUp")));
    }

    @Test
    void reportsStoredBackupVersionsForServerCatchUp() throws Exception {
        Path syncRoot = temp.resolve("CompanySync");
        Path source = write("accepted version");
        String checksum = HashUtil.sha256(source);
        LocalBackupStore.archiveVersion(syncRoot, 9, "Team", "Docs/a1.txt", source, 4, checksum);

        assertEquals(List.of(new LocalBackupStore.BackupVersion("Docs/a1.txt", 4)),
                LocalBackupStore.listVersions(syncRoot, 9, "Team"));
    }

    @Test
    void backupFolderIsExcludedFromSyncScanning() {
        assertTrue(SyncFileFilter.shouldIgnore(temp.resolve("CompanySync/BackUp/Team/a1.txt")));
        assertFalse(SyncFileFilter.shouldIgnore(temp.resolve("CompanySync/Team/a1.txt")));
    }

    private Path write(String content) throws Exception {
        Path file = Files.createTempFile(temp, "content-", ".txt");
        Files.writeString(file, content);
        return file;
    }
}
