package vn.edu.pbl4sync.common;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class RabinChunkerTest {
    @TempDir
    Path temp;

    @Test
    void chunksStayBelowMysqlDefaultPacketLimitAndCoverTheWholeFile() throws Exception {
        byte[] content = new byte[3 * 1024 * 1024];
        new Random(7).nextBytes(content);
        Path file = temp.resolve("large-file.bin");
        Files.write(file, content);

        var chunks = RabinChunker.chunk(file);
        long expectedOffset = 0;
        for (RabinChunker.Chunk chunk : chunks) {
            assertEquals(expectedOffset, chunk.offset());
            assertTrue(chunk.length() > 0);
            assertTrue(chunk.length() <= RabinChunker.MAX_SIZE);
            expectedOffset += chunk.length();
        }

        assertEquals(content.length, expectedOffset);
        assertTrue(chunks.size() > 1);
        assertTrue(RabinChunker.MAX_SIZE < 1024 * 1024);
    }
}
