package vn.edu.pbl4sync.server;

import vn.edu.pbl4sync.common.*;
import vn.edu.pbl4sync.server.db.ChunkStore;
import vn.edu.pbl4sync.server.db.Database;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import static vn.edu.pbl4sync.common.MessageTypes.*;

public class ServerCore implements AutoCloseable {
    private static final long CHUNK_UPLOAD_TTL_MILLIS = TimeUnit.MINUTES.toMillis(10);
    private static final int MAX_HEADER_SIZE = 4 * 1024 * 1024;
    private static final Pattern SHA256 = Pattern.compile("[0-9a-fA-F]{64}");
    private final ServerConfig config;
    private final ConnectionManager connections = new ConnectionManager();
    private final List<ServerLogListener> logListeners = new CopyOnWriteArrayList<>();
    private final Map<String, RelayTicket> relayTickets = new ConcurrentHashMap<>();
    private ExecutorService clientPool;
    private ExecutorService transferPool;
    private volatile boolean running;
    private ServerSocket serverSocket;
    private Database db;
    private ChunkStore chunkStore;
    private final ConcurrentMap<ClientSession, ConcurrentMap<String, PendingChunk>> pendingChunkUploads = new ConcurrentHashMap<>();

    public ServerCore(ServerConfig config) { this.config = config; }

    public synchronized void start() throws Exception {
        if (running) return;
        db = new Database(config.dbUrl(), config.dbUser(), config.dbPassword(), config.dbPoolSize(), config.initialAdminPassword());
        chunkStore = new ChunkStore(db);
        clientPool = Executors.newFixedThreadPool(config.clientThreads());
        transferPool = Executors.newFixedThreadPool(config.transferThreads());
        serverSocket = new ServerSocket(config.port());
        running = true;
        log("Server started on port " + config.port());
        Thread acceptThread = new Thread(this::acceptLoop, "server-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                socket.setKeepAlive(true);
                socket.setTcpNoDelay(true);
                clientPool.submit(new ClientHandler(this, socket));
            } catch (IOException e) {
                if (running) log("Accept error: " + e.getMessage());
            }
        }
    }

    public void handle(ClientSession session, ReceivedPacket received) throws Exception {
        Packet p = received.packet();
        if (!session.authenticated() && !LOGIN_REQUEST.equals(p.type())) {
            session.send(p.response(ERROR).with("message", "Please login first"));
            return;
        }

        switch (p.type()) {
            case LOGIN_REQUEST -> login(session, p);
            case DASHBOARD_REQUEST -> dashboard(session, p);
            case LIST_WORKSPACES_REQUEST -> listWorkspaces(session, p);
            case LIST_FILES_REQUEST -> listFiles(session, p);
            case FILE_VERSIONS_REQUEST -> listFileVersions(session, p);
            case LIST_MEMBERS_REQUEST -> listMembers(session, p);
            case LIST_USERS_REQUEST -> listUsers(session, p);
            case LIST_AGENTS_REQUEST -> listAgents(session, p);
            case LIST_ACTIVITY_REQUEST -> listActivity(session, p);
            case CREATE_USER_REQUEST -> createUser(session, p);
            case SET_USER_STATUS_REQUEST -> setUserStatus(session, p);
            case CREATE_WORKSPACE_REQUEST -> createWorkspace(session, p);
            case ADD_MEMBER_REQUEST -> addMember(session, p);
            case UPDATE_PERMISSION_REQUEST -> updatePermission(session, p);
            case REMOVE_MEMBER_REQUEST -> removeMember(session, p);
            case CHANGE_LEADER_REQUEST -> changeLeader(session, p);
            case DELETE_WORKSPACE_REQUEST -> deleteWorkspace(session, p);
            case CHUNK_QUERY -> chunkQuery(session, p);
            case CHUNK_UPLOAD -> chunkUpload(session, received);
            case FILE_EVENT -> fileEvent(session, received);
            case FILE_ROLLBACK_REQUEST -> rollbackFile(session, p);
            case FILE_APPLIED -> fileApplied(session, p);
            case FILE_RELAY_UPLOAD -> relayUpload(session, received);
            case SYNC_REPORT -> syncReport(session, p);
            default -> session.send(p.response(ERROR).with("message", "Unknown message type: " + p.type()));
        }
    }

    private void login(ClientSession s, Packet p) throws Exception {
        Map<String, String> user = db.authenticate(p.get("username"), p.get("password").toCharArray());
        if (user == null) {
            s.send(p.response(LOGIN_RESPONSE).with("success", false).with("message", "Invalid username/password or account disabled"));
            return;
        }
        long userId = Long.parseLong(user.get("id"));
        String device = p.get("deviceName").isBlank() ? "Unknown-PC" : p.get("deviceName");
        String ip = s.socket().getInetAddress().getHostAddress();
        long agentId = db.registerAgent(userId, device, ip);
        s.authenticate(userId, agentId, user.get("username"), user.get("system_role"), device);
        connections.add(s);
        db.logActivity(userId, null, "LOGIN", device + " connected from " + ip);
        log(user.get("username") + " logged in from " + device + " (agent " + agentId + ")");
        s.send(p.response(LOGIN_RESPONSE)
                .with("success", true)
                .with("userId", userId)
                .with("agentId", agentId)
                .with("username", user.get("username"))
                .with("systemRole", user.get("system_role")));
    }

    private void dashboard(ClientSession s, Packet p) throws Exception {
        Packet r = p.response(DASHBOARD_RESPONSE);
        db.dashboard(s.userId(), s.isAdmin(), connections.onlineCount()).forEach(r::with);
        s.send(r);
    }

    private void listWorkspaces(ClientSession s, Packet p) throws Exception {
        s.send(p.response(LIST_WORKSPACES_RESPONSE).with("rows", TableCodec.encode(db.listWorkspaces(s.userId(), s.isAdmin()))));
    }

    private void listFiles(ClientSession s, Packet p) throws Exception {
        long ws = p.getLong("workspaceId", 0);
        requireRead(s, ws);
        s.send(p.response(LIST_FILES_RESPONSE).with("rows", TableCodec.encode(db.listFiles(ws))));
    }

    private void listFileVersions(ClientSession s, Packet p) throws Exception {
        long fileId = p.getLong("fileId", 0);
        if (fileId <= 0) throw new IllegalArgumentException("Invalid file ID");
        Map<String, String> file = db.findFileById(fileId);
        if (file == null) throw new IllegalArgumentException("File does not exist");
        long workspaceId = parseLong(file.get("workspace_id"), 0);
        requireRead(s, workspaceId);
        s.send(p.response(FILE_VERSIONS_RESPONSE)
                .with("rows", TableCodec.encode(db.listFileVersions(fileId))));
    }

    private void listMembers(ClientSession s, Packet p) throws Exception {
        long ws = p.getLong("workspaceId", 0);
        if (!s.isAdmin() && !db.isMember(s.userId(), ws)) throw new SecurityException("Not a workspace member");
        s.send(p.response(LIST_MEMBERS_RESPONSE).with("rows", TableCodec.encode(db.listMembers(ws))));
    }

    private void listUsers(ClientSession s, Packet p) throws Exception {
        requireAdmin(s);
        s.send(p.response(LIST_USERS_RESPONSE).with("rows", TableCodec.encode(db.listUsers())));
    }

    private void listAgents(ClientSession s, Packet p) throws Exception {
        requireAdmin(s);
        s.send(p.response(LIST_AGENTS_RESPONSE).with("rows", TableCodec.encode(db.listAgents())));
    }

    private void listActivity(ClientSession s, Packet p) throws Exception {
        Long ws = p.get("workspaceId").isBlank() ? null : p.getLong("workspaceId", 0);
        if (ws != null && !s.isAdmin() && !db.isMember(s.userId(), ws)) throw new SecurityException("Not a workspace member");
        s.send(p.response(LIST_ACTIVITY_RESPONSE).with("rows", TableCodec.encode(db.listActivities(s.userId(), s.isAdmin(), ws, p.getInt("limit", 200)))));
    }

    private void createUser(ClientSession s, Packet p) throws Exception {
        requireAdmin(s);
        String username = p.get("username").trim();
        String password = p.get("password");
        if (username.isBlank() || password.length() < 4) throw new IllegalArgumentException("Username required and password must have at least 4 characters");
        long id = db.createUser(username, password.toCharArray(), p.get("role").isBlank() ? "USER" : p.get("role"));
        db.logActivity(s.userId(), null, "CREATE_USER", "Created user " + username);
        s.send(p.response(OK).with("id", id));
    }

    private void setUserStatus(ClientSession s, Packet p) throws Exception {
        requireAdmin(s);
        long targetUser = p.getLong("userId", 0);
        db.setUserStatus(targetUser, p.get("status"));
        if ("DISABLED".equalsIgnoreCase(p.get("status"))) for (ClientSession c : connections.byUser(targetUser)) c.close();
        db.logActivity(s.userId(), null, "USER_STATUS", "User " + p.get("userId") + " -> " + p.get("status"));
        s.send(p.response(OK));
    }

    private void createWorkspace(ClientSession s, Packet p) throws Exception {
        requireAdmin(s);
        long ws = db.createWorkspace(s.userId(), p.get("name").trim(), p.get("leaderUsername").trim());
        db.logActivity(s.userId(), ws, "CREATE_WORKSPACE", p.get("name") + " leader=" + p.get("leaderUsername"));
        s.send(p.response(OK).with("workspaceId", ws));
        broadcastWorkspacesChanged();
    }

    private void addMember(ClientSession s, Packet p) throws Exception {
        long ws = p.getLong("workspaceId", 0);
        requireWorkspaceManager(s, ws);
        db.addMember(ws, p.get("username"), "MEMBER",
                p.getBoolean("read", true), p.getBoolean("write", false), p.getBoolean("modify", false), p.getBoolean("delete", false));
        db.logActivity(s.userId(), ws, "ADD_MEMBER", p.get("username"));
        s.send(p.response(OK));
        notifyWorkspace(ws, "Workspace membership changed", -1);
        broadcastWorkspacesChanged();
    }

    private void updatePermission(ClientSession s, Packet p) throws Exception {
        long ws = p.getLong("workspaceId", 0);
        requireWorkspaceManager(s, ws);
        long targetUserId = p.getLong("userId", 0);
        if ("LEADER".equalsIgnoreCase(db.memberRole(ws, targetUserId))) throw new IllegalArgumentException("Leader permissions cannot be reduced");
        db.updatePermissions(ws, targetUserId, p.getBoolean("read", true), p.getBoolean("write", false),
                p.getBoolean("modify", false), p.getBoolean("delete", false));
        db.logActivity(s.userId(), ws, "UPDATE_PERMISSION", "user=" + p.get("userId"));
        s.send(p.response(OK));
    }

    private void removeMember(ClientSession s, Packet p) throws Exception {
        long ws = p.getLong("workspaceId", 0);
        requireWorkspaceManager(s, ws);
        db.removeMember(ws, p.getLong("userId", 0));
        db.logActivity(s.userId(), ws, "REMOVE_MEMBER", "user=" + p.get("userId"));
        s.send(p.response(OK));
        notifyWorkspace(ws, "Workspace membership changed", -1);
        broadcastWorkspacesChanged();
    }


    private void changeLeader(ClientSession s, Packet p) throws Exception {
        requireAdmin(s);
        long ws = p.getLong("workspaceId", 0);
        db.changeLeader(ws, p.get("leaderUsername").trim());
        db.logActivity(s.userId(), ws, "CHANGE_LEADER", p.get("leaderUsername"));
        s.send(p.response(OK));
        notifyWorkspace(ws, "Workspace leader changed", -1);
        broadcastWorkspacesChanged();
    }

    private void deleteWorkspace(ClientSession s, Packet p) throws Exception {
        requireAdmin(s);
        long ws = p.getLong("workspaceId", 0);
        db.logActivity(s.userId(), ws, "DELETE_WORKSPACE", "workspace=" + ws);
        db.deleteWorkspace(ws);
        s.send(p.response(OK));
        broadcastWorkspacesChanged();
    }

    private void fileEvent(ClientSession s, ReceivedPacket received) throws Exception {
        Packet p = received.packet();
        long ws = p.getLong("workspaceId", 0);
        String action = p.get("action").toUpperCase(Locale.ROOT);
        String path = sanitizeRelativePath(p.get("path"));
        Map<String, String> existingFile = db.findFile(ws, path);
        String permission;
        if ("DELETE".equals(action)) permission = "DELETE";
        else if ("CREATE".equals(action) || "MODIFY".equals(action)) {
            boolean existsActive = existingFile != null && !truthy(existingFile.get("deleted"));
            permission = existsActive ? "MODIFY" : "WRITE";
        } else throw new IllegalArgumentException("Invalid file action");
        if (!db.hasPermission(s.userId(), s.isAdmin(), ws, permission)) throw new SecurityException("Permission denied: " + permission);

        boolean deleted = "DELETE".equals(action);
        long baseVersion = p.getLong("baseVersion", 0);
        if (!deleted) {
            fileEventChunked(s, received, ws, path, action, baseVersion);
            return;
        }

        Path cached = null;
        long size = 0;
        String checksum = "";
        if (!deleted) {
            if (received.payloadPath() == null) throw new IllegalArgumentException("File payload required");
            size = Files.size(received.payloadPath());
            checksum = HashUtil.sha256(received.payloadPath());
            cached = Files.createTempFile("pbl4sync-relay-", ".tmp");
            Files.copy(received.payloadPath(), cached, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }

        Map<String, String> file = db.applyFileChange(ws, path, size, checksum, s.userId(), baseVersion, deleted);
        if (truthy(file.get("conflict"))) {
            if (cached != null) Files.deleteIfExists(cached);
            s.send(p.response(FILE_EVENT_RESULT)
                    .with("success", false)
                    .with("conflict", true)
                    .with("path", path)
                    .with("workspaceId", ws)
                    .with("baseVersion", baseVersion)
                    .with("currentVersion", file.getOrDefault("current_version", "0"))
                    .with("currentChecksum", file.getOrDefault("current_checksum", ""))
                    .with("currentDeleted", file.getOrDefault("current_deleted", "false")));
            return;
        }

        long fileId = Long.parseLong(file.get("id"));
        long version = Long.parseLong(file.get("version"));
        if (!deleted) db.markReplica(fileId, s.agentId(), version, checksum);
        db.logActivity(s.userId(), ws, action, path + " v" + version);

        Packet result = p.response(FILE_EVENT_RESULT).with("success", true).with("fileId", fileId).with("version", version)
                .with("checksum", checksum).with("size", size).with("path", path).with("workspaceId", ws);
        s.send(result);

        if (deleted) {
            Packet del = Packet.event(FILE_DELETE).with("workspaceId", ws).with("path", path).with("fileId", fileId).with("version", version);
            broadcastToWorkspace(ws, del, null, s.agentId());
        } else {
            Packet deliver = Packet.event(FILE_DELIVER).with("workspaceId", ws).with("path", path).with("fileId", fileId)
                    .with("version", version).with("checksum", checksum).with("size", size).with("conflict", false);
            broadcastToWorkspace(ws, deliver, cached, s.agentId());
            cached = null;
        }
        if (cached != null) Files.deleteIfExists(cached);
    }

    private void chunkQuery(ClientSession s, Packet p) throws Exception {
        long ws = p.getLong("workspaceId", 0);
        String path = sanitizeRelativePath(p.get("path"));
        String action = p.get("action").toUpperCase(Locale.ROOT);
        if (!"CREATE".equals(action) && !"MODIFY".equals(action)) {
            throw new IllegalArgumentException("CHUNK_QUERY action must be CREATE or MODIFY");
        }
        Map<String, String> existing = db.findFile(ws, path);
        boolean existsActive = existing != null && !truthy(existing.get("deleted"));
        String permission = existsActive ? "MODIFY" : "WRITE";
        if (!db.hasPermission(s.userId(), s.isAdmin(), ws, permission)) {
            throw new SecurityException("Permission denied: " + permission);
        }

        long baseVersion = requireNonNegativeLong(p, "baseVersion");
        long size = requireNonNegativeLong(p, "size");
        if (existing == null && baseVersion != 0) throw new IllegalArgumentException("baseVersion must be 0 for a new file");
        if (existing != null && baseVersion != parseLong(existing.get("version"), -1)) {
            throw new IllegalArgumentException("File version conflict; request a fresh sync state");
        }
        requireHash(p.get("checksum"), "File checksum");
        ChunkManifest manifest = parseRequestManifest(p);
        if (manifest.totalSize() != size) throw new IllegalArgumentException("Manifest size does not match size");
        validateManifestHashSizes(manifest);

        Set<String> missing = chunkStore.missingHashes(manifest.allHashes());
        List<String> orderedMissing = manifest.allHashes().stream().filter(missing::contains).distinct().toList();
        String missingHashes = String.join(",", orderedMissing);
        if (missingHashes.getBytes(StandardCharsets.UTF_8).length > MAX_HEADER_SIZE - 1024) {
            throw new IllegalArgumentException("CHUNK_QUERY_RESULT exceeds the 4 MiB protocol header limit");
        }

        long expiresAt = System.currentTimeMillis() + CHUNK_UPLOAD_TTL_MILLIS;
        ConcurrentMap<String, PendingChunk> staged = pendingChunkUploads.computeIfAbsent(s, ignored -> new ConcurrentHashMap<>());
        staged.clear();
        for (ChunkManifest.Entry chunk : manifest.chunks()) {
            if (missing.contains(chunk.hash())) staged.put(chunk.hash(), new PendingChunk(chunk.length(), expiresAt));
        }
        if (staged.isEmpty()) pendingChunkUploads.remove(s, staged);

        s.send(p.response(CHUNK_QUERY_RESULT)
                .with("missingHashes", missingHashes)
                .with("missingCount", orderedMissing.size()));
    }

    private void chunkUpload(ClientSession s, ReceivedPacket received) throws Exception {
        Packet p = received.packet();
        String hash = requireHash(p.get("hash"), "Chunk hash").toLowerCase(Locale.ROOT);
        int size = p.getInt("size", -1);
        if (size <= 0 || size > RabinChunker.MAX_SIZE) throw new IllegalArgumentException("Invalid chunk size");
        if (received.payloadPath() == null || received.payloadLength() <= 0) {
            throw new IllegalArgumentException("Chunk payload required");
        }
        if (received.payloadLength() != size || Files.size(received.payloadPath()) != size) {
            throw new IllegalArgumentException("Declared chunk size does not match payload");
        }

        ConcurrentMap<String, PendingChunk> staged = pendingChunkUploads.get(s);
        PendingChunk pending = staged == null ? null : staged.get(hash);
        if (pending == null || pending.expiresAt() < System.currentTimeMillis()) {
            if (staged != null && pending != null) staged.remove(hash, pending);
            throw new IllegalArgumentException("Chunk was not requested or upload authorization expired");
        }
        if (pending.size() != size) throw new IllegalArgumentException("Chunk size differs from CHUNK_QUERY manifest");

        byte[] content = Files.readAllBytes(received.payloadPath());
        chunkStore.saveChunk(hash, size, content);
        staged.remove(hash, pending);
        if (staged.isEmpty()) pendingChunkUploads.remove(s, staged);
        s.send(p.response(OK).with("hash", hash).with("size", size));
    }

    private void fileEventChunked(ClientSession s, ReceivedPacket received, long ws, String path,
                                  String action, long baseVersion) throws Exception {
        Packet p = received.packet();
        if (received.payloadLength() != 0) throw new IllegalArgumentException("Chunked FILE_EVENT must not include a file payload");
        ChunkManifest manifest = parseRequestManifest(p);
        long size = requireNonNegativeLong(p, "size");
        if (manifest.totalSize() != size) throw new IllegalArgumentException("Manifest size does not match size");
        validateManifestHashSizes(manifest);
        String checksum = requireHash(p.get("checksum"), "File checksum").toLowerCase(Locale.ROOT);

        Path assembled = Files.createTempFile("pbl4sync-chunked-", ".tmp");
        boolean handedToBroadcast = false;
        try {
            manifest.assembleTo(assembled, hash -> {
                try {
                    return chunkStore.loadChunk(hash);
                } catch (SQLException e) {
                    throw new IllegalStateException("Unable to load required chunk " + hash, e);
                }
            });
            if (Files.size(assembled) != size) throw new IllegalArgumentException("Assembled file size does not match manifest");
            String assembledChecksum = HashUtil.sha256(assembled);
            if (!assembledChecksum.equalsIgnoreCase(checksum)) {
                throw new IllegalArgumentException("Assembled file SHA-256 does not match FILE_EVENT checksum");
            }

            Map<String, String> file = db.applyFileChangeChunked(ws, path, size, checksum, s.userId(),
                    s.deviceName(), baseVersion, manifest);
            if (truthy(file.get("conflict"))) {
                s.send(p.response(FILE_EVENT_RESULT)
                        .with("success", false)
                        .with("conflict", true)
                        .with("path", path)
                        .with("workspaceId", ws)
                        .with("baseVersion", baseVersion)
                        .with("currentVersion", file.getOrDefault("current_version", "0"))
                        .with("currentChecksum", file.getOrDefault("current_checksum", ""))
                        .with("currentDeleted", file.getOrDefault("current_deleted", "false")));
                return;
            }

            long fileId = Long.parseLong(file.get("id"));
            long version = Long.parseLong(file.get("version"));
            db.markReplica(fileId, s.agentId(), version, checksum);
            db.logActivity(s.userId(), ws, action, path + " v" + version);
            s.send(p.response(FILE_EVENT_RESULT).with("success", true).with("fileId", fileId).with("version", version)
                    .with("checksum", checksum).with("size", size).with("path", path).with("workspaceId", ws));

            Packet deliver = Packet.event(FILE_DELIVER).with("workspaceId", ws).with("path", path).with("fileId", fileId)
                    .with("version", version).with("checksum", checksum).with("size", size).with("conflict", false);
            broadcastToWorkspace(ws, deliver, assembled, s.agentId());
            handedToBroadcast = true;
        } finally {
            if (!handedToBroadcast) Files.deleteIfExists(assembled);
        }
    }

    private void rollbackFile(ClientSession s, Packet p) throws Exception {
        long fileId = p.getLong("fileId", 0);
        long sourceVersion = p.getLong("version", 0);
        long baseVersion = requireNonNegativeLong(p, "baseVersion");
        long totalSize = requireNonNegativeLong(p, "totalSize");
        if (fileId <= 0 || sourceVersion <= 0) throw new IllegalArgumentException("Invalid file or source version");
        Map<String, String> current = db.findFileById(fileId);
        if (current == null) throw new IllegalArgumentException("File does not exist");
        long ws = parseLong(current.get("workspace_id"), 0);
        String path = sanitizeRelativePath(current.get("relative_path"));
        if (!db.hasPermission(s.userId(), s.isAdmin(), ws, "READ")) throw new SecurityException("Read permission denied");
        if (!db.hasPermission(s.userId(), s.isAdmin(), ws, "MODIFY")) throw new SecurityException("Modify permission denied");
        if (baseVersion != parseLong(current.get("version"), -1)) {
            s.send(p.response(OK).with("success", false).with("conflict", true)
                    .with("currentVersion", current.getOrDefault("version", "0"))
                    .with("currentChecksum", current.getOrDefault("checksum", "")));
            return;
        }

        ChunkManifest manifest = chunkStore.loadManifest(fileId, sourceVersion, totalSize);
        Path assembled = Files.createTempFile("pbl4sync-rollback-", ".tmp");
        boolean handedToBroadcast = false;
        try {
            manifest.assembleTo(assembled, hash -> {
                try {
                    return chunkStore.loadChunk(hash);
                } catch (SQLException e) {
                    throw new IllegalStateException("Unable to load required chunk " + hash, e);
                }
            });
            Map<String, String> result = db.copyFileVersion(fileId, sourceVersion, baseVersion,
                    s.userId(), s.deviceName());
            if (truthy(result.get("conflict"))) {
                s.send(p.response(OK).with("success", false).with("conflict", true)
                        .with("currentVersion", result.getOrDefault("current_version", "0"))
                        .with("currentChecksum", result.getOrDefault("current_checksum", "")));
                return;
            }

            long newVersion = Long.parseLong(result.get("version"));
            String checksum = result.getOrDefault("checksum", "");
            boolean deleted = truthy(result.get("deleted"));
            db.logActivity(s.userId(), ws, "ROLLBACK", path + " v" + newVersion + " from v" + sourceVersion);
            s.send(p.response(OK).with("success", true).with("fileId", fileId).with("version", newVersion)
                    .with("rollbackFromVersion", sourceVersion).with("checksum", checksum)
                    .with("size", result.getOrDefault("size", Long.toString(totalSize))));

            if (deleted) {
                Packet del = Packet.event(FILE_DELETE).with("workspaceId", ws).with("path", path)
                        .with("fileId", fileId).with("version", newVersion);
                broadcastToWorkspace(ws, del, null, -1);
            } else {
                Packet deliver = Packet.event(FILE_DELIVER).with("workspaceId", ws).with("path", path).with("fileId", fileId)
                        .with("version", newVersion).with("checksum", checksum).with("size", totalSize).with("conflict", false);
                broadcastToWorkspace(ws, deliver, assembled, -1);
                handedToBroadcast = true;
            }
        } finally {
            if (!handedToBroadcast) Files.deleteIfExists(assembled);
        }
    }

    private ChunkManifest parseRequestManifest(Packet p) {
        String text = p.get("manifest");
        int encodedSize = text.getBytes(StandardCharsets.UTF_8).length;
        if (encodedSize > MAX_HEADER_SIZE - 1024) {
            throw new IllegalArgumentException("Manifest exceeds the 4 MiB protocol header limit");
        }
        return ChunkManifest.parse(text);
    }

    private String requireHash(String hash, String label) {
        if (hash == null || !SHA256.matcher(hash).matches()) throw new IllegalArgumentException(label + " must be a 64-character SHA-256 hash");
        return hash;
    }

    private long requireNonNegativeLong(Packet p, String field) {
        String raw = p.get(field);
        try {
            long value = Long.parseLong(raw);
            if (value < 0) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid non-negative " + field);
        }
    }

    private void validateManifestHashSizes(ChunkManifest manifest) {
        Map<String, Integer> sizes = new HashMap<>();
        for (ChunkManifest.Entry chunk : manifest.chunks()) {
            Integer previous = sizes.putIfAbsent(chunk.hash(), chunk.length());
            if (previous != null && previous != chunk.length()) {
                throw new IllegalArgumentException("Manifest assigns multiple sizes to the same chunk hash");
            }
        }
    }

    private void fileApplied(ClientSession s, Packet p) throws Exception {
        long fileId = p.getLong("fileId", 0);
        Map<String, String> file = db.findFileById(fileId);
        if (file == null) return;
        long ws = parseLong(file.get("workspace_id"), 0);
        if (!s.isAdmin() && !db.hasPermission(s.userId(), false, ws, "READ")) return;
        if (parseLong(file.get("version"), -1) != p.getLong("version", -2)) return;
        if (!Objects.equals(file.getOrDefault("checksum", ""), p.get("checksum"))) return;
        db.markReplica(fileId, s.agentId(), p.getLong("version", 0), p.get("checksum"));
    }

    private void relayUpload(ClientSession source, ReceivedPacket received) throws Exception {
        Packet p = received.packet();
        if (received.payloadPath() == null) return;
        String ticketId = p.get("ticket");
        RelayTicket ticket = relayTickets.remove(ticketId);
        if (ticket == null || ticket.expiresAt() < System.currentTimeMillis()) return;
        if (ticket.sourceAgentId() != source.agentId()) return;
        long targetAgentId = p.getLong("targetAgentId", 0);
        if (ticket.targetAgentId() != targetAgentId) return;
        ClientSession target = connections.byAgent(targetAgentId);
        if (target == null) return;

        long ws = p.getLong("workspaceId", 0);
        if (ticket.workspaceId() != ws || ticket.fileId() != p.getLong("fileId", 0) || ticket.version() != p.getLong("version", 0)) return;
        if (!db.isMember(target.userId(), ws) && !target.isAdmin()) return;
        Path cached = Files.createTempFile("pbl4sync-relay-", ".tmp");
        Files.copy(received.payloadPath(), cached, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Packet deliver = Packet.event(FILE_DELIVER)
                .with("workspaceId", ws).with("path", sanitizeRelativePath(p.get("path")))
                .with("fileId", p.get("fileId")).with("version", p.get("version"))
                .with("checksum", p.get("checksum")).with("size", Files.size(cached))
                .with("conflict", p.getBoolean("conflict", false));
        sendAndDelete(target, deliver, cached);
    }

    private void syncReport(ClientSession s, Packet p) throws Exception {
        List<Map<String, String>> localRows = TableCodec.decode(p.get("rows"));
        Map<String, Map<String, String>> local = new HashMap<>();
        for (Map<String, String> r : localRows) {
            long ws = parseLong(r.get("workspace_id"), 0);
            if (!s.isAdmin() && !db.isMember(s.userId(), ws)) continue;
            String path = sanitizeRelativePath(r.getOrDefault("path", ""));
            local.put(ws + "|" + path, r);
        }

        List<Map<String, String>> serverFiles = db.filesForUser(s.userId(), s.isAdmin());
        Set<String> knownServerKeys = new HashSet<>();
        int actions = 0;

        for (Map<String, String> f : serverFiles) {
            long ws = parseLong(f.get("workspace_id"), 0);
            String path = f.get("relative_path");
            String key = ws + "|" + path;
            knownServerKeys.add(key);
            Map<String, String> l = local.get(key);
            boolean deleted = truthy(f.get("deleted"));
            long serverVersion = parseLong(f.get("version"), 0);

            if (deleted) {
                if (l != null) {
                    s.send(Packet.event(FILE_DELETE).with("workspaceId", ws).with("path", path).with("fileId", f.get("id")).with("version", serverVersion));
                    actions++;
                }
                continue;
            }

            if (l == null) {
                if (requestCurrentReplica(s, f, false)) actions++;
                continue;
            }

            String localChecksum = l.getOrDefault("checksum", "");
            long knownVersion = parseLong(l.get("known_version"), 0);
            if (Objects.equals(localChecksum, f.getOrDefault("checksum", ""))) {
                db.markReplica(parseLong(f.get("id"), 0), s.agentId(), serverVersion, localChecksum);
                s.send(Packet.event(SYNC_STATE).with("workspaceId", ws).with("path", path).with("version", serverVersion).with("checksum", localChecksum));
            } else if (knownVersion == serverVersion) {
                if (db.hasPermission(s.userId(), s.isAdmin(), ws, "MODIFY")) {
                    s.send(Packet.event(FILE_UPLOAD_REQUEST)
                            .with("workspaceId", ws)
                            .with("path", path)
                            .with("action", "MODIFY"));
                    actions++;
                } else {
                    s.send(Packet.event(CONFLICT_NOTICE)
                            .with("workspaceId", ws)
                            .with("path", path)
                            .with("message", "Local file changed but this user has no MODIFY permission"));
                    if (requestCurrentReplica(s, f, true)) actions++;
                }
            } else if (knownVersion > serverVersion) {
                s.send(Packet.event(CONFLICT_NOTICE)
                        .with("workspaceId", ws)
                        .with("path", path)
                        .with("message", "Local state is newer than server metadata; refusing automatic overwrite"));
                if (requestCurrentReplica(s, f, true)) actions++;
            } else {
                s.send(Packet.event(CONFLICT_NOTICE).with("workspaceId", ws).with("path", path).with("message", "Local and server versions both changed"));
                if (requestCurrentReplica(s, f, true)) actions++;
            }
        }

        for (Map.Entry<String, Map<String, String>> e : local.entrySet()) {
            if (knownServerKeys.contains(e.getKey())) continue;
            Map<String, String> l = e.getValue();
            long ws = parseLong(l.get("workspace_id"), 0);
            if (db.hasPermission(s.userId(), s.isAdmin(), ws, "WRITE")) {
                s.send(Packet.event(FILE_UPLOAD_REQUEST).with("workspaceId", ws).with("path", l.get("path")).with("action", "CREATE"));
                actions++;
            }
        }

        s.send(p.response(SYNC_REPORT_RESULT).with("success", true).with("actions", actions));
    }

    private boolean requestCurrentReplica(ClientSession target, Map<String, String> file, boolean conflict) throws Exception {
        long fileId = parseLong(file.get("id"), 0);
        long version = parseLong(file.get("version"), 0);
        for (Long agentId : db.replicaAgents(fileId, version)) {
            ClientSession source = connections.byAgent(agentId);
            if (source == null || source.agentId() == target.agentId()) continue;
            String ticketId = UUID.randomUUID().toString();
            relayTickets.put(ticketId, new RelayTicket(ticketId, source.agentId(), target.agentId(), fileId, version, parseLong(file.get("workspace_id"),0), System.currentTimeMillis()+120_000));
            Packet fetch = Packet.event(FILE_FETCH_REQUEST)
                    .with("ticket", ticketId)
                    .with("targetAgentId", target.agentId())
                    .with("workspaceId", file.get("workspace_id"))
                    .with("path", file.get("relative_path"))
                    .with("fileId", fileId)
                    .with("version", version)
                    .with("checksum", file.get("checksum"))
                    .with("size", file.get("size"))
                    .with("conflict", conflict);
            source.send(fetch);
            return true;
        }
        target.send(Packet.event(NOTIFICATION).with("level", "WARN").with("message",
                "Cannot sync " + file.get("relative_path") + ": no online Agent currently holds version " + version));
        return false;
    }

    private void broadcastToWorkspace(long workspaceId, Packet packet, Path payload, long excludeAgentId) throws Exception {
        List<ClientSession> recipients = new ArrayList<>();
        for (Long userId : db.workspaceUserIds(workspaceId)) {
            if (!db.hasPermission(userId, false, workspaceId, "READ")) continue;
            for (ClientSession s : connections.byUser(userId)) {
                if (s.agentId() != excludeAgentId && s.isOpen()) recipients.add(s);
            }
        }
        if (payload == null) {
            for (ClientSession r : recipients) {
                transferPool.submit(() -> { try { r.send(packet); } catch (Exception e) { log("Send failed: " + e.getMessage()); } });
            }
            return;
        }
        if (recipients.isEmpty()) { Files.deleteIfExists(payload); return; }
        AtomicInteger left = new AtomicInteger(recipients.size());
        for (ClientSession r : recipients) {
            transferPool.submit(() -> {
                try { r.send(packet, payload); }
                catch (Exception e) { log("File send failed to " + r.username() + ": " + e.getMessage()); }
                finally {
                    if (left.decrementAndGet() == 0) try { Files.deleteIfExists(payload); } catch (IOException ignored) { }
                }
            });
        }
    }

    private void sendAndDelete(ClientSession target, Packet packet, Path payload) {
        transferPool.submit(() -> {
            try { target.send(packet, payload); }
            catch (Exception e) { log("Relay failed: " + e.getMessage()); }
            finally { try { Files.deleteIfExists(payload); } catch (IOException ignored) { } }
        });
    }

    private void broadcastWorkspacesChanged() {
        for (ClientSession c : connections.all()) {
            try { c.send(Packet.event(WORKSPACES_CHANGED)); } catch (Exception ignored) { }
        }
    }

    private void notifyWorkspace(long ws, String message, long excludeAgent) throws Exception {
        broadcastToWorkspace(ws, Packet.event(NOTIFICATION).with("level", "INFO").with("message", message), null, excludeAgent);
    }

    private void requireAdmin(ClientSession s) { if (!s.isAdmin()) throw new SecurityException("Admin only"); }
    private void requireWorkspaceManager(ClientSession s, long ws) throws Exception {
        if (!s.isAdmin() && !db.isLeader(s.userId(), ws)) throw new SecurityException("Leader/Admin only");
    }
    private void requireRead(ClientSession s, long ws) throws Exception {
        if (!db.hasPermission(s.userId(), s.isAdmin(), ws, "READ")) throw new SecurityException("Read permission denied");
    }

    private String sanitizeRelativePath(String raw) {
        String p = raw == null ? "" : raw.replace('\\', '/');
        while (p.startsWith("/")) p = p.substring(1);
        if (p.isBlank() || p.contains("../") || p.equals("..")) throw new IllegalArgumentException("Invalid path");
        return p;
    }
    private boolean truthy(String s) { return "1".equals(s) || "true".equalsIgnoreCase(s); }
    private long parseLong(String s, long fallback) { try { return Long.parseLong(s); } catch (Exception e) { return fallback; } }

    public void onDisconnected(ClientSession s) {
        pendingChunkUploads.remove(s);
        connections.remove(s);
        if (s.authenticated()) {
            try {
                db.markAgentOffline(s.agentId());
                db.logActivity(s.userId(), null, "LOGOUT", s.deviceName() + " disconnected");
            } catch (Exception ignored) { }
            log(s.username() + " disconnected (" + s.deviceName() + ")");
        }
    }

    public void addLogListener(ServerLogListener l) { logListeners.add(l); }
    public void removeLogListener(ServerLogListener l) { logListeners.remove(l); }
    public void log(String msg) {
        String line = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")) + "  " + msg;
        System.out.println(line);
        for (ServerLogListener l : logListeners) l.onLog(line);
    }

    public int onlineCount() { return connections.onlineCount(); }
    public boolean isRunning() { return running; }
    public int port() { return config.port(); }

    public synchronized void stop() {
        if (!running) return;
        running = false;
        try { serverSocket.close(); } catch (Exception ignored) { }
        for (ClientSession s : connections.all()) s.close();
        if (clientPool != null) clientPool.shutdownNow();
        if (transferPool != null) transferPool.shutdownNow();
        if (db != null) {
            try { db.markAllAgentsOffline(); } catch (Exception ignored) { }
            db.close();
        }
        log("Server stopped");
    }

    private record PendingChunk(int size, long expiresAt) { }
    private record RelayTicket(String id, long sourceAgentId, long targetAgentId, long fileId, long version, long workspaceId, long expiresAt) { }

    @Override public void close() { stop(); }
}
