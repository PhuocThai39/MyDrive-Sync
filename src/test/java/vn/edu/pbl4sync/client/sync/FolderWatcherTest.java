package vn.edu.pbl4sync.client.sync;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class FolderWatcherTest {
    @TempDir
    Path temp;

    @Test
    void browserDownloadDoesNotTurnReplacementIntoDelete() throws Exception {
        Path target = temp.resolve("data_50mb.txt");
        Path partialDownload = temp.resolve("data_50mb.txt.crdownload");
        Files.writeString(target, "previous content");

        AtomicBoolean deleted = new AtomicBoolean();
        CountDownLatch created = new CountDownLatch(1);
        IgnoreRegistry ignore = new IgnoreRegistry();

        try (FolderWatcher watcher = new FolderWatcher(temp, ignore, (action, path) -> {
            if ("DELETE".equals(action)) deleted.set(true);
            else created.countDown();
        })) {
            Thread.sleep(150);
            Files.delete(target);
            Files.writeString(partialDownload, "download in progress");

            Thread.sleep(1300);
            assertFalse(deleted.get(), "A browser partial download must defer the delete event");

            Files.move(partialDownload, target);
            assertTrue(created.await(3, TimeUnit.SECONDS), "The completed download should be observed");
            assertFalse(deleted.get(), "The replacement must not be reported as a delete");
        }
    }

    @Test
    void atomicSaveWithArbitraryTemporaryNameDoesNotTurnReplacementIntoDelete() throws Exception {
        Path target = temp.resolve("data_50mb.txt");
        Path temporarySave = temp.resolve("notepad-save-123.tmp");
        Files.writeString(target, "previous content");

        AtomicBoolean deleted = new AtomicBoolean();
        CountDownLatch replaced = new CountDownLatch(1);
        IgnoreRegistry ignore = new IgnoreRegistry();

        try (FolderWatcher watcher = new FolderWatcher(temp, ignore, (action, path) -> {
            if ("DELETE".equals(action)) deleted.set(true);
            else replaced.countDown();
        })) {
            Thread.sleep(150);
            Files.delete(target);
            Files.writeString(temporarySave, "new content");

            Thread.sleep(1300);
            assertFalse(deleted.get(), "A recent sibling temporary-file write must defer delete");

            Files.move(temporarySave, target);
            assertTrue(replaced.await(3, TimeUnit.SECONDS), "The replacement should be observed");
            assertFalse(deleted.get(), "The replacement must not be reported as a delete");
        }
    }
}
