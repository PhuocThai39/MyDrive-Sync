package vn.edu.pbl4sync.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vn.edu.pbl4sync.client.NetworkClient;
import vn.edu.pbl4sync.common.MessageTypes;
import vn.edu.pbl4sync.common.Packet;

import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class RollbackSyncIntegrationTest {
    @TempDir
    Path temp;

    @Test
    void rollbackBroadcastsRestoredContentAsANewVersion() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        String dbUrl = "jdbc:h2:mem:rollback_sync_" + System.nanoTime()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE";
        ServerCore server = new ServerCore(new ServerConfig(port, dbUrl, "sa", "",
                "admin123", 4, 8, 2));
        NetworkClient client = new NetworkClient();
        CountDownLatch deliveryReceived = new CountDownLatch(1);
        AtomicReference<String> deliveredContent = new AtomicReference<>();
        AtomicReference<String> deliveredVersion = new AtomicReference<>();
        AtomicReference<Exception> deliveryFailure = new AtomicReference<>();
        client.setEventHandler(received -> {
            if (!MessageTypes.FILE_DELIVER.equals(received.packet().type())) return;
            try {
                deliveredContent.set(Files.readString(received.payloadPath()));
                deliveredVersion.set(received.packet().get("version"));
            } catch (Exception e) {
                deliveryFailure.set(e);
            } finally {
                deliveryReceived.countDown();
            }
        });

        try {
            server.start();
            client.connect("127.0.0.1", port);
            Packet login = client.request(Packet.request(MessageTypes.LOGIN_REQUEST)
                    .with("username", "admin").with("password", "admin123")
                    .with("deviceName", "RollbackTest"), Duration.ofSeconds(10));
            assertEquals(MessageTypes.LOGIN_RESPONSE, login.type());
            assertTrue(login.getBoolean("success", false));

            Packet workspace = client.request(Packet.request(MessageTypes.CREATE_WORKSPACE_REQUEST)
                    .with("name", "Rollback Integration").with("leaderUsername", "admin"),
                    Duration.ofSeconds(10));
            assertEquals(MessageTypes.OK, workspace.type());
            long workspaceId = workspace.getLong("workspaceId", 0);

            Path first = write("version-one");
            Packet firstResult = upload(client, workspaceId, first, 0);
            assertEquals("1", firstResult.get("version"));
            Path second = write("version-two");
            Packet secondResult = upload(client, workspaceId, second, 1);
            assertEquals("2", secondResult.get("version"));

            Packet rollback = client.request(Packet.request(MessageTypes.FILE_ROLLBACK_REQUEST)
                    .with("workspaceId", workspaceId).with("path", "a1.txt")
                    .with("targetVersion", 1).with("currentVersion", 2), Duration.ofSeconds(10));
            assertEquals(MessageTypes.FILE_ROLLBACK_RESULT, rollback.type());
            assertTrue(rollback.getBoolean("success", false));
            assertEquals("3", rollback.get("version"));
            assertTrue(deliveryReceived.await(10, TimeUnit.SECONDS));
            if (deliveryFailure.get() != null) throw deliveryFailure.get();
            assertEquals("version-one", deliveredContent.get());
            assertEquals("3", deliveredVersion.get());
        } finally {
            client.close();
            server.close();
        }
    }

    private Packet upload(NetworkClient client, long workspaceId, Path payload, long baseVersion) throws Exception {
        return client.request(Packet.request(MessageTypes.FILE_EVENT).with("workspaceId", workspaceId)
                .with("path", "a1.txt").with("action", baseVersion == 0 ? "CREATE" : "MODIFY")
                .with("baseVersion", baseVersion), payload, Duration.ofSeconds(10));
    }

    private Path write(String content) throws Exception {
        Path file = Files.createTempFile(temp, "version-", ".txt");
        Files.writeString(file, content);
        return file;
    }
}
