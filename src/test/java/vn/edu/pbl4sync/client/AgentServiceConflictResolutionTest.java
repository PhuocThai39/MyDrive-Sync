package vn.edu.pbl4sync.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vn.edu.pbl4sync.client.sync.LocalStateStore;
import vn.edu.pbl4sync.common.HashUtil;
import vn.edu.pbl4sync.common.Packet;
import vn.edu.pbl4sync.common.ProtocolIO;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static vn.edu.pbl4sync.common.MessageTypes.FILE_APPLIED;
import static vn.edu.pbl4sync.common.MessageTypes.FILE_DELIVER;

class AgentServiceConflictResolutionTest {
    @TempDir
    Path temp;

    @Test
    void appliesAuthoritativeConflictDeliveryWithSameVersion() throws Exception {
        Path syncRoot = temp.resolve("CompanySync");
        ClientConfig config = new ClientConfig(temp.resolve("client.properties"));
        config.setSyncRoot(syncRoot);

        try (ServerSocket server = new ServerSocket(0);
             AgentService service = new AgentService(config)) {
            Path incoming = temp.resolve("incoming.txt");
            Files.writeString(incoming, "mac1");
            String remoteChecksum = HashUtil.sha256(incoming);

            Path localFile = syncRoot.resolve("7_bin").resolve("bintest.txt");
            Files.createDirectories(localFile.getParent());
            Files.writeString(localFile, "win2");

            LocalStateStore state = new LocalStateStore(syncRoot);
            state.put(7, "bintest.txt", 2, remoteChecksum);
            setField(service, "state", state);
            setField(service, "workspaces", List.of(Map.of("id", "7", "name", "bin")));

            CompletableFuture<Packet> applied = CompletableFuture.supplyAsync(() -> {
                try (Socket socket = server.accept();
                     var received = ProtocolIO.receive(socket.getInputStream())) {
                    return received.packet();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            NetworkClient network = (NetworkClient) getField(service, "network");
            network.connect("127.0.0.1", server.getLocalPort());

            Packet delivery = Packet.event(FILE_DELIVER)
                    .with("workspaceId", 7)
                    .with("path", "bintest.txt")
                    .with("version", 2)
                    .with("checksum", remoteChecksum)
                    .with("fileId", 11)
                    .with("conflict", true);
            Method apply = AgentService.class.getDeclaredMethod("applyDeliveredFile", Packet.class, Path.class);
            apply.setAccessible(true);
            apply.invoke(service, delivery, incoming);

            assertEquals("mac1", Files.readString(localFile));
            assertEquals(2, state.version(7, "bintest.txt"));
            assertEquals(remoteChecksum, state.checksum(7, "bintest.txt"));
            assertEquals(FILE_APPLIED, applied.get(5, TimeUnit.SECONDS).type());

            Path conflictDirectory = syncRoot.resolve(".pbl4sync").resolve("conflicts").resolve("7");
            try (var conflicts = Files.list(conflictDirectory)) {
                Path conflict = conflicts.findFirst().orElseThrow();
                assertEquals("win2", Files.readString(conflict));
            }
            assertFalse(Files.exists(syncRoot.resolve("BackUp")));
        }
    }

    private static Object getField(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
