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
import static vn.edu.pbl4sync.common.MessageTypes.FILE_EVENT_RESULT;
import static vn.edu.pbl4sync.common.MessageTypes.SYNC_REPORT_RESULT;

class AgentServiceConflictResolutionTest {
    @TempDir
    Path temp;

    @Test
    void movesUploadedConflictAsideBeforeApplyingWinningServerFile() throws Exception {
        Path syncRoot = temp.resolve("CompanySync");
        ClientConfig config = new ClientConfig(temp.resolve("client.properties"));
        config.setSyncRoot(syncRoot);

        try (ServerSocket server = new ServerSocket(0);
             AgentService service = new AgentService(config)) {
            Path localFile = syncRoot.resolve("7_bin").resolve("bintest.txt");
            Files.createDirectories(localFile.getParent());
            Files.writeString(localFile, "second device version");
            String localChecksum = HashUtil.sha256(localFile);

            LocalStateStore state = new LocalStateStore(syncRoot);
            state.put(7, "bintest.txt", 1, localChecksum);
            setField(service, "state", state);
            setField(service, "workspaces", List.of(Map.of("id", "7", "name", "bin")));

            String conflictPath = "bintest_conflict_LaptopA_20261106_100513.txt";
            CompletableFuture<Packet> applied = CompletableFuture.supplyAsync(() -> {
                try (Socket socket = server.accept();
                     var upload = ProtocolIO.receive(socket.getInputStream())) {
                    ProtocolIO.send(socket.getOutputStream(), upload.packet().response(FILE_EVENT_RESULT)
                            .with("conflict", true)
                            .with("conflictPath", conflictPath)
                            .with("currentVersion", 2), null);
                    try (var sync = ProtocolIO.receive(socket.getInputStream())) {
                        ProtocolIO.send(socket.getOutputStream(),
                                sync.packet().response(SYNC_REPORT_RESULT)
                                        .with("success", true).with("actions", 0), null);
                    }
                    try (var ack = ProtocolIO.receive(socket.getInputStream())) {
                        return ack.packet();
                    }
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });

            NetworkClient network = (NetworkClient) getField(service, "network");
            network.connect("127.0.0.1", server.getLocalPort());

            Method upload = AgentService.class.getDeclaredMethod(
                    "uploadLocalChange", long.class, String.class, String.class);
            upload.setAccessible(true);
            upload.invoke(service, 7L, "bintest.txt", "MODIFY");

            Path conflictFile = localFile.resolveSibling(conflictPath);
            assertFalse(Files.exists(localFile));
            assertEquals("second device version", Files.readString(conflictFile));

            Path winner = temp.resolve("winner.txt");
            Files.writeString(winner, "server winning version");
            Packet delivery = Packet.event(FILE_DELIVER)
                    .with("workspaceId", 7)
                    .with("path", "bintest.txt")
                    .with("version", 2)
                    .with("checksum", HashUtil.sha256(winner))
                    .with("fileId", 11);
            Method apply = AgentService.class.getDeclaredMethod("applyDeliveredFile", Packet.class, Path.class);
            apply.setAccessible(true);
            apply.invoke(service, delivery, winner);

            assertEquals(FILE_APPLIED, applied.get(5, TimeUnit.SECONDS).type());
            assertEquals("server winning version", Files.readString(localFile));
            assertEquals("second device version", Files.readString(conflictFile));
            assertEquals(2, state.version(7, "bintest.txt"));
            assertEquals(HashUtil.sha256(winner), state.checksum(7, "bintest.txt"));
        }
    }

    @Test
    void appliesConflictCopyWithoutReplacingOriginalFile() throws Exception {
        Path syncRoot = temp.resolve("CompanySync");
        ClientConfig config = new ClientConfig(temp.resolve("client.properties"));
        config.setSyncRoot(syncRoot);

        try (AgentService service = new AgentService(config)) {
            Path incoming = temp.resolve("conflict-incoming.txt");
            Files.writeString(incoming, "second device version");
            String checksum = HashUtil.sha256(incoming);

            Path original = syncRoot.resolve("7_bin").resolve("bintest.txt");
            Files.createDirectories(original.getParent());
            Files.writeString(original, "server winning version");

            LocalStateStore state = new LocalStateStore(syncRoot);
            state.put(7, "bintest.txt", 3, HashUtil.sha256(original));
            setField(service, "state", state);
            setField(service, "workspaces", List.of(Map.of("id", "7", "name", "bin")));

            String conflictPath = "bintest_conflict_LaptopA_20261106_100513.txt";
            Packet delivery = Packet.event(FILE_DELIVER)
                    .with("workspaceId", 7)
                    .with("path", conflictPath)
                    .with("version", 1)
                    .with("checksum", checksum)
                    .with("conflict", true)
                    .with("conflictCopy", true)
                    .with("originalPath", "bintest.txt")
                    .with("conflictDevice", "LaptopA");
            Method apply = AgentService.class.getDeclaredMethod("applyDeliveredFile", Packet.class, Path.class);
            apply.setAccessible(true);
            apply.invoke(service, delivery, incoming);

            assertEquals("server winning version", Files.readString(original));
            assertEquals("second device version",
                    Files.readString(syncRoot.resolve("7_bin").resolve(conflictPath)));
            assertEquals(1, state.version(7, conflictPath));
            assertEquals(checksum, state.checksum(7, conflictPath));
        }
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Object getField(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }
}
